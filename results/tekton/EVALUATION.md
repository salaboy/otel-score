# OpenTelemetry Support Maturity Evaluation: Tekton Pipelines

## Project overview

- **Project**: Tekton Pipelines — a Kubernetes-native CI/CD framework providing CRDs (Task, Pipeline, TaskRun, PipelineRun) and controllers that reconcile them, executing workloads as Kubernetes pods
- **Version evaluated**: v1.11.1
- **Evaluation date**: 2026-05-01
- **Cluster**: otel-eval-tekton
- **Maturity model version**: OpenTelemetry Support Maturity Model for CNCF Projects (draft)

## Summary

| Dimension | Level | Summary |
|-----------|-------|---------|
| Integration Surface | 2 | OTLP HTTP traces + Prometheus metrics; dual config systems; no OTLP logs |
| Semantic Conventions | 2 | OTel SDK used natively; HTTP metrics follow current semconv; trace span attributes use custom keys |
| Resource Attributes & Configuration | 1 | `service.name` set natively; no `service.version` in traces; no `OTEL_*` env var support; identity inconsistent across signals |
| Trace Modeling & Context Propagation | 3 | Excellent: cross-service traces, W3C propagation to TaskRun pods, deep reconciler span trees |
| Multi-Signal Observability | 2 | Two signals flowing (traces + metrics); logs are stdout-only with no OTel export; no trace-log correlation |
| Audience & Signal Quality | 2 | Reconciler traces are operationally useful; span names are internal code paths; no error status codes on failed runs |
| Stability & Change Management | 2 | Metrics documented as "experimental"; migration guide published for OTel transition; no schemaUrl on traces; no stability guarantees |

## Telemetry overview

### Signals observed
- **Traces**: Flowing — OTLP HTTP to `otel-collector:4318/v1/traces` via `config-tracing` ConfigMap
- **Metrics**: Flowing — Prometheus scrape at `:9090` on controller, webhook, and events-controller services
- **Logs**: Not flowing — structured JSON to stdout only; no OTLP export path

### Resource attributes (native, before collector enrichment)

From Prometheus scrape target labels (Tekton-native in metrics):
- `service.name`: `tekton-pipelines-controller` / `tekton-pipelines-webhook` / `tekton-events-controller`
- `service.instance.id`: `<svc-fqdn>:9090` (Prometheus scrape target)
- `service_name`: `tekton-pipelines-controller` (duplicate, non-standard underscore form)
- `service_version`: `5a88281` (git commit hash, non-standard underscore form)
- `telemetry_sdk_language`: `go`
- `telemetry_sdk_name`: `opentelemetry`
- `telemetry_sdk_version`: `1.42.0`
- `k8s_namespace_name`, `k8s_pod_name` (Prometheus target labels, underscore form)

From OTLP trace export (Tekton-native in traces):
- `service.name`: `taskrun-reconciler` / `pipelinerun-reconciler`

Note: `service.name` differs between traces (`taskrun-reconciler`) and metrics (`tekton-pipelines-controller`) for the same controller process — inconsistent identity.

### Resource attributes (after collector enrichment)

After k8sattributes processor adds:
- `k8s.namespace.name`, `k8s.pod.name`, `k8s.pod.uid`, `k8s.pod.start_time`
- `k8s.deployment.name`, `k8s.replicaset.name`, `k8s.node.name`, `k8s.container.name`
- `k8s.pod.label.*` (all pod labels including `app.kubernetes.io/version: v1.11.1`, `pipeline.tekton.dev/release: v1.11.1`)

## Dimension evaluations

### 1. Integration Surface

**Level: 2 — Structured OTel Integration**

#### Evidence

**Traces**: Tekton exports traces via OTLP HTTP (not gRPC) to a configurable endpoint. The endpoint is set via the `config-tracing` ConfigMap with keys `enabled: "true"` and `endpoint: "http://...:4318/v1/traces"`. This uses the `otlptracehttp` exporter from the OTel Go SDK. Traces are confirmed flowing with 8 JSONL batches containing 2,217 spans from two reconciler services.

**Metrics**: Exposed as Prometheus format at `:9090` on three services (controller, webhook, events-controller). Configured via `config-observability` ConfigMap with `metrics-protocol: prometheus`. The OTel SDK is used internally (`telemetry_sdk_name: opentelemetry`, `telemetry_sdk_version: 1.42.0`), with Prometheus as the export format. 96 unique metrics collected including 11 Tekton-specific ones.

**Logs**: No OTLP log export. Logs go to stdout as structured JSON. No configuration option to send logs to an OTLP endpoint.

**Dual tracing systems**: v1.11.1 has two tracing config paths:
1. Legacy `config-tracing` ConfigMap (`enabled`/`endpoint` keys) — uses `otlptracehttp`, confirmed working
2. New `config-observability` ConfigMap (`tracing-protocol`/`tracing-endpoint` keys) — documented but not yet effective in v1.11.1

**OTLP metrics**: The `config-observability` ConfigMap documents `metrics-protocol: grpc` and `metrics-protocol: http/protobuf` options for OTLP metrics export, but only `prometheus` was tested and confirmed working. This suggests OTLP metrics push is available but not yet the primary path.

#### Checklist assessment
- ✅ Emits traces via OTLP (HTTP)
- ✅ Emits metrics via Prometheus (OTel SDK internally)
- ✅ Telemetry endpoint is configurable (ConfigMap-based)
- ✅ Dynamic config reload (no controller restart required)
- ❌ No OTLP log export
- ❌ gRPC OTLP traces not confirmed (only HTTP)
- ⚠️ Dual tracing config systems creates confusion

#### Rationale
Level 2 is appropriate. Tekton has deliberate, native OTLP integration for traces and uses the OTel SDK for metrics (even if exposed via Prometheus). The absence of OTLP logs and the dual-system confusion prevent Level 3. The Prometheus-only metrics export (no confirmed OTLP push) also limits the score.

---

### 2. Semantic Conventions

**Level: 2 — Mostly Conventional**

#### Evidence

##### Trace attributes
Span attribute keys observed (all spans):
- `taskrun`: TaskRun name (e.g., `hello-taskrun-1`) — custom key, not OTel semconv
- `namespace`: Kubernetes namespace — custom key (OTel semconv would be `k8s.namespace.name`)
- `pipelinerun`: PipelineRun name — custom key, not OTel semconv

Instrumentation scope names:
- `TaskRunReconciler` (no version)
- `PipelineRunReconciler` (no version)

Schema URL on traces: `https://opentelemetry.io/schemas/1.12.0` — present but outdated (current is 1.28.0+)

All spans use `kind=1` (INTERNAL). Root spans (`TaskRun:Reconciler`, `PipelineRun:Reconciler`) are also INTERNAL — correct for controller reconciler operations (these are not server-side request handlers).

No error status codes set on any spans (all `UNSET`), even for failed TaskRuns — this is a semconv gap.

##### Metric names and attributes
Tekton-specific metrics use a custom `tekton_pipelines_controller_*` prefix — not OTel semconv, but consistent and descriptive. Attributes on Tekton metrics: `namespace`, `task`, `pipeline`, `status`, `reason` — custom keys, not OTel semconv.

HTTP metrics (from `otelhttp` instrumentation library) use **current OTel semconv**:
- `http.request.method` ✅ (not deprecated `http.method`)
- `http.response.status_code` ✅ (not deprecated `http.status_code`)
- `http_route` ✅
- `network.protocol.name`, `network.protocol.version` ✅
- `server.address`, `server.port` ✅

Metric schema URL: `https://opentelemetry.io/schemas/1.18.0` — present on metrics.

Go runtime metrics (`go_*`) and workqueue metrics (`kn_workqueue_*`) use OTel community conventions.

##### Log attributes
No OTLP logs. Stdout logs use `knative.dev/traceid` (UUID format, not OTel trace ID format) — not OTel semconv.

#### Checklist assessment
- ✅ HTTP metrics use current OTel semconv (`http.request.method` etc.)
- ✅ Schema URL present on both traces and metrics
- ✅ OTel SDK v1.42.0 used natively
- ❌ Trace span attributes use custom keys (`taskrun`, `namespace`) instead of `k8s.taskrun.name`, `k8s.namespace.name`
- ❌ No error status codes on failed runs (semconv requires `STATUS_CODE_ERROR`)
- ❌ Instrumentation scope has no version
- ❌ Trace schema URL is outdated (1.12.0 vs current 1.28.0+)
- ❌ Metric attributes on Tekton metrics use custom keys, not semconv

#### Rationale
Level 2 is appropriate. The HTTP instrumentation layer (from `otelhttp`) correctly uses current semconv. The OTel SDK is used natively. However, the core Tekton-specific telemetry (span attributes, metric attribute names) uses custom keys rather than OTel semantic conventions, and error status codes are missing on failed reconciliations.

---

### 3. Resource Attributes & Configuration

**Level: 1 — Basic Identity**

#### Evidence

##### Native resource attributes (traces)
Only `service.name` is set natively in traces:
- `taskrun-reconciler` (for TaskRun reconciler)
- `pipelinerun-reconciler` (for PipelineRun reconciler)

No `service.version`, `service.instance.id`, or `telemetry.sdk.*` in trace resource attributes.

##### Native resource attributes (metrics via Prometheus)
Prometheus scrape adds target labels which become resource attributes:
- `service.name`: `tekton-pipelines-controller`
- `service.instance.id`: `tekton-pipelines-controller.tekton-pipelines.svc.cluster.local:9090`
- `service_name`: `tekton-pipelines-controller` (duplicate, underscore form — non-standard)
- `service_version`: `5a88281` (git commit hash, underscore form — non-standard key name)
- `telemetry_sdk_language`: `go`, `telemetry_sdk_name`: `opentelemetry`, `telemetry_sdk_version`: `1.42.0`

##### Identity inconsistency across signals
- **Traces**: `service.name = taskrun-reconciler` / `pipelinerun-reconciler`
- **Metrics**: `service.name = tekton-pipelines-controller` / `tekton-pipelines-webhook`

The same controller process reports different `service.name` values in traces vs metrics. This makes cross-signal correlation by `service.name` impossible without additional knowledge.

##### OTEL_* environment variable support
No `OTEL_*` environment variables found in the controller deployment spec. Configuration is entirely ConfigMap-based (`config-tracing`, `config-observability`). Standard `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, or `OTEL_EXPORTER_OTLP_ENDPOINT` are not supported.

##### service.version
- Not present in trace resource attributes
- Present in metrics as `service_version: 5a88281` (git commit hash, non-standard key)
- Not set to the release version string (`v1.11.1`)

#### Checklist assessment
- ✅ `service.name` set natively in traces
- ✅ OTel SDK identity attributes visible in metrics (`telemetry_sdk_*`)
- ❌ `service.name` inconsistent between traces and metrics
- ❌ `service.version` missing from traces; only git commit hash in metrics
- ❌ No `service.instance.id` in traces
- ❌ No `OTEL_*` environment variable support
- ❌ `service_version` uses underscore form (not OTel standard dot notation)

#### Rationale
Level 1 is the correct assessment. Basic service identity (`service.name`) is set in traces, but the inconsistency between signals, missing `service.version`, and absence of `OTEL_*` env var support prevent Level 2. The ConfigMap-based configuration is project-specific rather than the OTel standard configuration interface.

---

### 4. Trace Modeling & Context Propagation

**Level: 3 — Exemplary Trace Modeling**

#### Evidence

##### Span structure
Traces reveal a well-structured reconciler hierarchy for both TaskRun and PipelineRun operations:

**TaskRun trace tree** (root: `TaskRun:Reconciler`):
```
TaskRun:Reconciler [ROOT, INTERNAL]
├── updateTaskRunWithDefaultWorkspaces
├── prepare
├── createPod
├── reconcile
├── updateLabelsAndAnnotations
├── finishReconcileUpdateEmitEvents
├── durationAndCountMetrics
└── TaskRun:ReconcileKind
```

**PipelineRun trace tree** (root: `PipelineRun:Reconciler`):
```
PipelineRun:Reconciler [ROOT, INTERNAL]
├── resolvePipelineState
├── runNextSchedulableTask
├── createTaskRuns → createTaskRun
├── updatePipelineRunStatusFromInformer
├── updateLabelsAndAnnotations
├── finishReconcileUpdateEmitEvents
├── durationAndCountMetrics
└── PipelineRun:ReconcileKind
    └── [child TaskRun spans from taskrun-reconciler service]
```

17 unique span names across 2 services. Span events are present on root spans (`updating TaskRun status with SpanContext`, `updating PipelineRun status with SpanContext`).

##### Cross-service trace correlation
**Confirmed**: PipelineRun traces span two services. A single PipelineRun trace (`bc5bc7d6...`) contains spans from both `pipelinerun-reconciler` and `taskrun-reconciler`. The `createTaskRun` span in the PipelineRun trace is the parent of the TaskRun reconciler's root span.

##### W3C Trace Context propagation to workloads
**Confirmed**: Tekton propagates W3C `traceparent` to TaskRun pods via the annotation `tekton.dev/taskrunSpanContext`:
```json
{"traceparent":"00-bc5bc7d6c3b854e62765f43daf6d71eb-a9d36b98b9e75609-01"}
```
This allows user workloads running inside TaskRun pods to join the parent pipeline trace. The trace ID in the annotation matches the PipelineRun trace ID — confirmed end-to-end.

##### Span kind correctness
All spans are `INTERNAL` (kind=1). This is correct for controller reconciler operations — these are not server-side request handlers.

#### Checklist assessment
- ✅ Root spans with meaningful names for each reconciliation
- ✅ Deep, logical span hierarchy representing internal operations
- ✅ Cross-service traces: PipelineRun + TaskRun spans in single trace
- ✅ W3C Trace Context propagated to user workloads via annotation
- ✅ Span events present on reconciler root spans
- ✅ Correct span kinds (INTERNAL for controller operations)
- ⚠️ No error status codes on failed reconciliations (all spans UNSET even for fail-taskrun-1)
- ⚠️ Span attributes use custom keys (`taskrun`, `namespace`) not semconv

#### Rationale
Level 3 is appropriate. The trace modeling is genuinely excellent: cross-service traces that span the full pipeline execution, W3C context propagation to user workloads enabling distributed tracing into user code, and a logical span hierarchy that reflects real reconciler operations. The missing error status codes are a gap but don't undermine the overall excellent trace architecture.

---

### 5. Multi-Signal Observability

**Level: 2 — Two Signals with Limited Correlation**

#### Evidence

##### Signal availability
| Signal | Status | Method | Count |
|--------|--------|--------|-------|
| Traces | Flowing | OTLP HTTP | 17 unique span names, 2 services |
| Metrics | Flowing | Prometheus scrape | 96 metrics (11 Tekton-specific) |
| Logs | Not flowing | stdout JSON only | 0 OTLP records |

##### Cross-signal correlation
- **Trace-metric correlation**: Impossible by `service.name` — traces use `taskrun-reconciler` while metrics use `tekton-pipelines-controller` for the same process. No shared identity attribute between the two signals.
- **Trace-log correlation**: Not possible. Logs use `knative.dev/traceid` (UUID format, e.g., `5c3888ed-4346-4f32-8dad-e1e747f85a83`) which is an internal Knative identifier, not the OTel trace ID (hex, e.g., `bc5bc7d6c3b854e62765f43daf6d71eb`). The two formats cannot be correlated.
- **Metric-log correlation**: No shared identity.

##### Collection model
- Traces: OTLP push (HTTP) — modern, OTel-native
- Metrics: Prometheus pull — requires collector scrape configuration
- Logs: Requires external log collection (node-level log scraping) — no native OTLP path

##### Metric coverage
Tekton-specific metrics cover key operational dimensions:
- Duration histograms: `tekton_pipelines_controller_taskrun_duration_seconds`, `pipelinerun_duration_seconds`
- Counters: `taskrun_total`, `pipelinerun_total` (with `status`, `reason` labels)
- Gauges: `running_taskruns`, `running_pipelineruns`, `running_*_waiting_on_*_resolution`
- Pod latency: `taskruns_pod_latency_milliseconds`

#### Checklist assessment
- ✅ Two signals flowing (traces + metrics)
- ✅ Prometheus metrics cover core operational dimensions
- ✅ OTel SDK used for both traces and metrics internally
- ❌ No OTLP log export
- ❌ Cross-signal correlation broken: `service.name` differs between traces and metrics
- ❌ Log trace IDs use different format (UUID) than OTel trace IDs (hex)
- ❌ Prometheus pull requires collector configuration; not OTLP push

#### Rationale
Level 2 is appropriate. Two signals are flowing with meaningful content, but the broken cross-signal correlation (different `service.name` in traces vs metrics) and the absence of log export prevent Level 3. An operator cannot correlate a specific trace with its associated metrics or logs without additional tooling.

---

### 6. Audience & Signal Quality

**Level: 2 — Operationally Useful**

#### Evidence

##### Span naming
Span names reflect internal reconciler code paths but are operationally meaningful:
- `TaskRun:Reconciler`, `TaskRun:ReconcileKind` — clear entry points
- `prepare`, `createPod`, `reconcile` — logical operations
- `resolvePipelineState`, `runNextSchedulableTask` — pipeline-specific operations
- `updateTaskRunWithDefaultWorkspaces`, `updateLabelsAndAnnotations` — detailed internal steps
- `finishReconcileUpdateEmitEvents`, `durationAndCountMetrics` — lifecycle operations

The naming follows a mix of high-level operations and internal function names. Most are understandable to a Tekton operator without needing source code access.

##### Signal-to-noise ratio
- **Traces**: 17 unique span names, all relevant to understanding reconciler behavior. Low noise. The reconciler loop generates multiple spans per reconciliation cycle (visible in the data: `TaskRun:ReconcileKind` appears 219 times across 8 batches), which is expected for controller patterns.
- **Metrics**: 96 total metrics, 11 Tekton-specific. The remaining 85 are Go runtime (`go_*`), Knative infrastructure (`kn_*`), HTTP (`http_*`), and k8s cluster metrics. This is a reasonable signal-to-noise ratio for a controller.

##### Default usability
- Metrics are documented in `docs/metrics.md` with a table of names, types, and labels
- The `docs/metrics-migration-otel.md` provides a clear migration guide
- Traces are not documented with span names or attribute descriptions
- No dashboards or alert rules provided in the repository
- The dual tracing config systems (`config-tracing` vs `config-observability`) create confusion for operators

##### Error observability
- **Critical gap**: Failed TaskRuns (e.g., `fail-taskrun-1`) do not result in error status codes on spans. All 2,217 spans have `status.code = UNSET`. Operators cannot use trace status to identify failed reconciliations.
- The `tekton_pipelines_controller_taskrun_total{status="failed"}` metric does count failures correctly.

#### Checklist assessment
- ✅ Span names are operationally meaningful (not just function names)
- ✅ Metrics cover the key CI/CD operational questions (duration, counts, running, waiting)
- ✅ Metric documentation exists with types and labels
- ✅ Cross-service traces provide end-to-end pipeline visibility
- ❌ No error span status codes — failed runs are invisible in traces
- ❌ Trace span attributes lack context (only `taskrun` name and `namespace`)
- ❌ No dashboards or alert rules provided
- ❌ Dual tracing config systems create operator confusion
- ❌ Trace behavior not documented (no span catalog)

#### Rationale
Level 2 is appropriate. The telemetry is genuinely useful for understanding pipeline execution — the cross-service traces and metric coverage give operators real visibility. However, the missing error status codes on traces and the lack of trace documentation prevent Level 3.

---

### 7. Stability & Change Management

**Level: 2 — Documented with Caveats**

#### Evidence

##### Documentation of telemetry behavior
- `docs/metrics.md`: Lists all 14 Tekton-specific metrics with names, types, label descriptions, and **explicit "experimental" status** for all metrics
- `docs/metrics-migration-otel.md`: Detailed migration guide for the OpenCensus → OpenTelemetry transition, documenting all breaking changes with old/new metric name tables
- No equivalent documentation for traces (no span catalog, no attribute documentation)
- `config-observability.yaml` and `config-tracing.yaml` ConfigMaps serve as inline configuration reference

##### Change communication
- `docs/metrics-migration-otel.md` explicitly documents breaking metric name changes with a "Breaking Changes - Action Required" header
- Documents the `metrics.backend-destination` → `metrics-protocol` configuration key rename
- Provides a quick-reference checklist for migration
- No equivalent trace change documentation found

##### Schema URL presence
- **Traces**: `schemaUrl: https://opentelemetry.io/schemas/1.12.0` — present but outdated (1.12.0 is from 2021; current is 1.28.0+)
- **Metrics**: `schemaUrl: https://opentelemetry.io/schemas/1.18.0` — present, more recent

##### Stability guarantees
- All Tekton-specific metrics are explicitly marked **"experimental"** in `docs/metrics.md`
- No stability guarantee for trace span names or attributes
- The dual tracing config system (`config-tracing` vs `config-observability`) is an active transition — the old `config-tracing` system is not deprecated in documentation

##### OTel SDK version
- `telemetry_sdk_version: 1.42.0` (Go OTel SDK) — current and actively maintained

#### Checklist assessment
- ✅ Metrics documented with types, labels, and status
- ✅ Breaking changes explicitly documented with migration guide
- ✅ Schema URL present on both traces and metrics
- ✅ OTel SDK version is current
- ❌ All metrics marked "experimental" — no stability guarantee
- ❌ Trace behavior not documented (no span catalog)
- ❌ Trace schema URL is outdated (1.12.0)
- ❌ Dual tracing config systems not clearly documented as deprecated/transitioning

#### Rationale
Level 2 is appropriate. Tekton has made genuine investment in change communication — the OpenCensus-to-OTel migration guide is exemplary. However, the "experimental" label on all metrics, absence of trace documentation, and outdated trace schema URL prevent Level 3.

---

## Key findings

### Strengths

1. **Excellent trace modeling and cross-service propagation**: PipelineRun traces span both `pipelinerun-reconciler` and `taskrun-reconciler` services in a single coherent trace. W3C `traceparent` is propagated to TaskRun pods via annotations, enabling user workloads to join the pipeline trace — a genuinely advanced feature.

2. **Native OTel SDK usage**: Tekton uses the Go OTel SDK (v1.42.0) natively for both traces and metrics. HTTP metrics follow current OTel semantic conventions (`http.request.method`, `http.response.status_code`). The schema URL is present on both signals.

3. **Well-documented metrics migration**: The `docs/metrics-migration-otel.md` provides a clear, actionable guide for the OpenCensus → OTel transition with explicit breaking change tables, configuration key renames, and a migration checklist. This is a model for how to communicate telemetry breaking changes.

### Areas for improvement

1. **Fix cross-signal identity inconsistency**: `service.name` should be consistent between traces (`taskrun-reconciler`) and metrics (`tekton-pipelines-controller`) for the same process. Add `service.version: v1.11.1` to trace resource attributes. This is the single highest-impact improvement for operator usability.

2. **Set error status codes on failed reconciliations**: All spans currently use `STATUS_CODE_UNSET` even when TaskRuns fail. Spans for failed reconciliations should set `STATUS_CODE_ERROR` with a descriptive message. Without this, traces cannot be used to detect failures.

3. **Export logs via OTLP or add trace context to stdout logs**: Either add OTLP log export capability (using the OTel Go log bridge), or replace `knative.dev/traceid` (UUID format) in stdout logs with the OTel trace ID (hex format) to enable trace-log correlation.

### Notable observations

- **Dual tracing systems**: v1.11.1 has two parallel tracing configuration paths — the legacy `config-tracing` ConfigMap (confirmed working) and the new `config-observability` tracing keys (documented but not yet effective). This creates operator confusion and should be resolved in a future release with explicit deprecation of the legacy path.

- **`service.name` divergence is intentional**: The traces emit `taskrun-reconciler` and `pipelinerun-reconciler` as service names (reflecting the reconciler role), while metrics use `tekton-pipelines-controller` (reflecting the deployment name). This appears to be a deliberate design choice but breaks cross-signal correlation.

- **Prometheus labels use underscore notation**: Resource attributes from Prometheus scrape use underscore-separated keys (`service_name`, `service_version`, `telemetry_sdk_language`) rather than the OTel dot-notation standard. This is a Prometheus-to-OTel translation artifact, not a Tekton bug, but it results in duplicate attributes (`service.name` and `service_name`).

- **OTel SDK version is current**: The use of Go OTel SDK v1.42.0 demonstrates active maintenance of the instrumentation layer.

## Methodology notes

- Telemetry was collected using an OpenTelemetry Collector (v0.150.1) with file export in a kind cluster
- The k8sattributes processor was used to distinguish native vs enriched resource attributes
- Traffic was generated via 6 TaskRuns, 5 PipelineRuns (10 child TaskRuns), and 1 failing TaskRun
- Semantic conventions were checked against the latest stable OpenTelemetry specification
- Documentation was reviewed at the `v1.11.1` git tag
- The `config-tracing` ConfigMap was used for tracing (OTLP HTTP); `config-observability` was used for metrics (Prometheus)
