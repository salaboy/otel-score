# OpenTelemetry Support Maturity Evaluation: Jaeger

## Project overview

- **Project**: Jaeger — a distributed tracing platform for monitoring and troubleshooting microservices. Originally developed at Uber, donated to CNCF. Jaeger v2 is a complete rewrite built on top of the OpenTelemetry Collector framework.
- **Version evaluated**: v2.17.0 (Helm chart 4.7.0)
- **Evaluation date**: 2026-04-23
- **Cluster**: otel-eval-jaeger (kind)
- **Maturity model version**: OpenTelemetry Support Maturity Model for CNCF Projects (draft)

## Summary

| Dimension | Level | Summary |
|-----------|-------|---------|
| Integration Surface | 2 | OTLP is the primary interface; Prometheus also available; legacy protocols (Jaeger, Zipkin) remain for receiver compatibility |
| Semantic Conventions | 2 | Current OTel semconv used consistently for HTTP spans and metrics; schemaUrl set; no deprecated attributes observed |
| Resource Attributes & Configuration | 2 | `service.name`, `service.version`, `service.instance.id` set natively; `OTEL_TRACES_SAMPLER` and `OTEL_EXPORTER_OTLP_*` respected |
| Trace Modeling & Context Propagation | 1 | Self-traces cover Query service HTTP operations; trace context accepted on all receiver protocols; but self-traces not exported externally and trace modeling is limited to HTTP entry points |
| Multi-Signal Observability | 1 | Metrics are first-class (OTLP + Prometheus); self-traces stored internally only; logs are stdout-only with no OTLP export; no cross-signal correlation |
| Audience & Signal Quality | 2 | Metrics are operational and pipeline-oriented; self-traces use logical HTTP operation names with current semconv; metrics level configurable; sensible defaults |
| Stability & Change Management | 2 | Telemetry changes appear in release notes; metrics snapshot CI testing in place; schemaUrl set; Prometheus mixin with alerts and Grafana dashboard provided; legacy v1 alert rules not yet updated for v2 |

## Telemetry overview

### Signals observed
- **Traces**: Flowing (self-traces) — stored internally in Jaeger's own storage; NOT exported to external OTLP backends by default. Jaeger accepts traces from external services via OTLP, Jaeger, and Zipkin protocols.
- **Metrics**: Flowing — dual export: OTLP push (periodic reader) + Prometheus pull (port 8888)
- **Logs**: Not flowing via OTLP — stdout only (structured JSON with embedded OTel resource fields)

### Resource attributes (native, before collector enrichment)
From OTLP push metrics (what Jaeger emits natively):
- `service.name: jaeger`
- `service.version: v2.17.0`
- `service.instance.id: 80720aff-6281-4cb1-9b71-8d5bd6dcec17` (UUID, stable per process)
- `server.address: jaeger.jaeger.svc.cluster.local`
- `server.port: 8888`
- `url.scheme: http`

From self-traces (stored internally, viewed via Jaeger Query API):
- `host.name: jaeger-84d6554b64-qqh4k`
- `os.type: linux`
- `telemetry.sdk.language: go`
- `telemetry.sdk.name: opentelemetry`
- `telemetry.sdk.version: 1.42.0`

Note: `service.name` and `service.version` are configured in `service.telemetry.resource` but appear in the internal trace representation without those attributes in the process tags (the Jaeger Query API representation differs from raw OTLP).

### Resource attributes (after collector enrichment)
After k8sattributes processing, the following are added by the collector:
- `k8s.namespace.name: jaeger`
- `k8s.pod.name: jaeger-84d6554b64-qqh4k`
- `k8s.pod.uid: ed28d981-a65b-4482-b9f5-0ba40b2ea4a7`
- `k8s.deployment.name: jaeger`
- `k8s.replicaset.name: jaeger-84d6554b64`
- `k8s.node.name: otel-eval-jaeger-control-plane`
- `k8s.container.name: jaeger`
- `k8s.pod.start_time`
- Pod labels: `app.kubernetes.io/name=jaeger`, `app.kubernetes.io/instance=jaeger`, `app.kubernetes.io/component=all-in-one`
- Pod annotations: `prometheus.io/scrape=true`, `prometheus.io/port=8888`

---

## Dimension evaluations

### 1. Integration Surface

**Level: 2 — OpenTelemetry-Native**

#### Evidence

Jaeger v2 is itself an OpenTelemetry Collector distribution. Its entire architecture is built on the OTel Collector framework. Telemetry configuration uses the standard OTel Collector `service.telemetry` config block:

```yaml
service:
  telemetry:
    resource:
      service.name: jaeger
    metrics:
      level: detailed
      readers:
        - pull:
            exporter:
              prometheus:
                host: 0.0.0.0
                port: 8888
        - periodic:
            interval: 15000
            exporter:
              otlp:
                protocol: grpc
                endpoint: http://otel-collector-...:4317
```

- **OTLP is the primary metric export path**: Both OTLP push (periodic reader) and Prometheus pull are natively supported. OTLP push is the forward-looking default.
- **Prometheus remains available**: Port 8888 is advertised via pod annotations (`prometheus.io/scrape=true`, `prometheus.io/port=8888`), making it compatible with existing Prometheus-based stacks.
- **Legacy receiver protocols**: Jaeger accepts traces via OTLP (primary), Jaeger Thrift/gRPC (legacy), and Zipkin — these are for *receiving* traces from instrumented services, not for Jaeger's own telemetry export.
- **OTLP env vars respected**: `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_EXPORTER_OTLP_*` are all documented and respected (confirmed in PR #8208 and official docs).
- **Documentation**: The official docs (`/docs/2.17/operations/monitoring/`) explicitly reference OTel Collector documentation for telemetry configuration and list `otelcol_*` metrics as the primary monitoring surface.

#### Checklist assessment

- ✅ OTLP export supported and recommended
- ✅ Standard OTel configuration mechanisms used (`service.telemetry` block)
- ✅ Can connect to existing OTel Collectors without adapters
- ✅ `OTEL_TRACES_SAMPLER`, `OTEL_EXPORTER_OTLP_*` env vars respected
- ✅ Prometheus scraping also available (not required)
- ⚠️ Legacy receiver protocols (Jaeger, Zipkin) remain first-class for *receiving* traces — but this is appropriate for a tracing backend
- ⚠️ Self-traces not exported to external OTLP backends by default (stored internally)

#### Rationale

Jaeger v2 is OTel-Native by architecture — it IS an OTel Collector distribution. OTLP is the primary integration surface for both metric export and trace ingestion. The project uses standard OTel configuration mechanisms throughout. The legacy Jaeger/Zipkin receiver protocols are kept for backward compatibility with instrumented services, which is a legitimate design choice for a tracing backend. The absence of external OTLP export for self-traces is a constraint of the current architecture (self-traces loop back into the same process), not a lack of OTel commitment. Level 2 is well-earned; Level 3 would require explicit documentation of the integration surface as a stable contract and more deliberate deprecation of legacy receivers.

---

### 2. Semantic Conventions

**Level: 2 — OpenTelemetry-Native**

#### Evidence

##### Trace attributes (self-traces)
Jaeger's self-traces (Query service HTTP operations) use **current, stable OTel semantic conventions**:
- `http.request.method: GET` ✅ (not deprecated `http.method`)
- `http.response.status_code: 200` ✅ (not deprecated `http.status_code`)
- `url.path: /api/traces` ✅ (not deprecated `http.target`)
- `url.scheme: http` ✅
- `server.address: localhost` ✅
- `server.port: 16686` ✅
- `network.peer.address` ✅
- `network.peer.port` ✅
- `network.protocol.version: 1.1` ✅
- `client.address` ✅
- `user_agent.original` ✅
- `http.response.body.size` (note: this is an OTel semconv attribute)
- Instrumentation scope: `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.67.0`

No deprecated HTTP attributes (`http.method`, `http.status_code`, `http.url`, `http.target`) were observed.

##### Metric names and attributes
Metrics from OTLP push (native, pre-enrichment):
- `http.server.request.duration_seconds` (histogram) — note: Prometheus suffix added by scrape; native name is `http.server.request.duration` ✅
- `http.server.request.body.size_bytes` → native: `http.server.request.body.size` ✅
- `http.server.response.body.size_bytes` → native: `http.server.response.body.size` ✅
- `otelcol_receiver_accepted_spans` (OTel Collector internal metrics) ✅
- `otelcol_exporter_sent_spans` ✅
- `otelcol_process_cpu_seconds` ✅
- `otelcol_processor_batch_batch_send_size` ✅

Metric data point attributes:
- `http.request.method` ✅ (current semconv)
- `http.response.status_code` ✅ (current semconv)
- `network.protocol.name`, `network.protocol.version` ✅
- `server.address`, `server.port` ✅
- `url.scheme` ✅

**Schema URL**: `https://opentelemetry.io/schemas/1.38.0` (for HTTP/server metrics) and `https://opentelemetry.io/schemas/1.18.0` (for OTel Collector internal metrics). Schema URLs are set consistently.

##### Log attributes
Logs go to stdout in structured JSON with embedded OTel resource fields:
```json
{"level": "info", "ts": "...", "msg": "...", 
 "resource": {"service.name": "jaeger", "service.version": "v2.17.0", "service.instance.id": "..."},
 "otelcol.component.id": "...", "otelcol.component.kind": "..."}
```
Log fields follow OTel conventions where applicable (resource fields use OTel attribute names). However, logs are not exported via OTLP, so they cannot be evaluated for OTLP semantic conventions.

#### Checklist assessment

- ✅ Current stable OTel HTTP semantic conventions used (http.request.method, http.response.status_code, url.path, url.scheme)
- ✅ No deprecated attributes observed (no http.method, http.status_code, http.target, http.url)
- ✅ schemaUrl set on OTLP exports
- ✅ Metric attribute keys align with current semconv
- ✅ Instrumentation scope name and version set
- ⚠️ Log semantic conventions cannot be fully assessed (no OTLP log export)
- ⚠️ `otelcol_*` metric names are OTel Collector internal conventions, not OTel semantic conventions per se — but they are consistent and documented

#### Rationale

Jaeger v2 uses current, non-deprecated OTel semantic conventions throughout its observable telemetry. The HTTP metrics and self-trace span attributes all use the current stable semconv. Schema URLs are set. The only gap is that logs are not exported via OTLP, preventing full assessment of log semantic conventions. Level 2 is appropriate; Level 3 would require domain-specific semantic extensions (e.g., jaeger.* attributes for tracing backend concepts like storage operations, query patterns) documented as explicit contracts.

---

### 3. Resource Attributes & Configuration

**Level: 2 — OpenTelemetry-Native**

#### Evidence

##### Native resource attributes
From OTLP push metrics (what Jaeger emits natively, before collector enrichment):
- `service.name: jaeger` — stable, meaningful, consistent
- `service.version: v2.17.0` — set from binary version
- `service.instance.id: 80720aff-6281-4cb1-9b71-8d5bd6dcec17` — UUID, stable per process lifecycle

These three core identity attributes are set natively and consistently in the `service.telemetry.resource` config block.

##### OTEL_* environment variable support
Confirmed supported (documented and verified):
- `OTEL_TRACES_SAMPLER` — controls self-trace sampling (e.g., `always_off`, `parentbased_always_on`, `jaeger_remote`)
- `OTEL_TRACES_SAMPLER_ARG` — sampler arguments
- `OTEL_EXPORTER_OTLP_*` — standard OTLP exporter configuration
- `OTEL_RESOURCE_ATTRIBUTES` — respected via OTel Collector SDK

The config explicitly sets `service.name` in `service.telemetry.resource`, which takes precedence over `OTEL_SERVICE_NAME` — this is standard OTel Collector behavior and is documented.

##### Identity consistency across signals
- **Metrics (OTLP push)**: `service.name=jaeger`, `service.version=v2.17.0`, `service.instance.id=<UUID>` ✅
- **Metrics (Prometheus scrape)**: Same values, enriched by k8sattributes ✅
- **Traces (self-traces, internal)**: `service.name=jaeger` in process tags; `telemetry.sdk.*` in resource ✅
- **Logs (stdout)**: `service.name`, `service.version`, `service.instance.id` embedded in JSON ✅

Identity is consistent across all signals.

#### Checklist assessment

- ✅ `service.name` stable and meaningful
- ✅ `service.version` set (v2.17.0)
- ✅ `service.instance.id` set (UUID, per-process)
- ✅ Identity consistent across metrics, traces, and logs
- ✅ `OTEL_TRACES_SAMPLER` and `OTEL_EXPORTER_OTLP_*` respected
- ✅ Identity attributes on resource, not on spans
- ⚠️ Configuration precedence between `service.telemetry.resource` and `OTEL_SERVICE_NAME` is not explicitly documented but follows OTel Collector conventions
- ⚠️ Kubernetes resource attributes not emitted natively (expected — pipeline-derived via k8sattributes)

#### Rationale

Jaeger v2 sets the three core identity attributes (`service.name`, `service.version`, `service.instance.id`) natively and consistently across all signals. Standard `OTEL_*` env vars are respected. The project delegates to the OTel Collector SDK for configuration handling, which is the correct approach. Level 2 is appropriate. Level 3 would require explicit documentation of configuration precedence, stability guarantees for identity attributes, and guidance for multi-tenant deployments.

---

### 4. Trace Modeling & Context Propagation

**Level: 1 — OpenTelemetry-Aligned**

#### Evidence

##### Span structure (self-traces)
Jaeger v2 self-traces cover HTTP operations on the Query service (`/api/traces`, `/api/services`, etc.):
- Span names: `/api/traces`, `/api/services` — URL path-based, which is functional but not ideal (could be `GET /api/traces`)
- Span kind: `server` ✅
- Single-span traces (no parent-child relationships observed for these API calls)
- Instrumentation via `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp` — standard OTel Go HTTP instrumentation

##### Context propagation
Jaeger accepts trace context on all receiver protocols:
- **OTLP**: W3C TraceContext (`traceparent` header) — VERIFIED (HTTP 200 responses when sending with traceparent)
- **Zipkin**: B3 format — VERIFIED (HTTP 202 response)
- **Jaeger Thrift**: Jaeger propagation format — VERIFIED (port active, HTTP 405 on GET is expected behavior)
- Jaeger does NOT propagate context outbound (it is a terminal backend, not a proxy)

##### Trace coherence
- Self-traces are stored in Jaeger's own storage backend, not exported to external OTLP collectors
- The self-tracing loop prevention (PR #8208 in v2.17.0) ensures Jaeger's internal OTLP receiver does not create recursive traces
- Default sampler: `parentbased_always_on` — traces incoming requests that carry trace context
- `OTEL_TRACES_SAMPLER=always_off` disables self-tracing (documented)
- Self-tracing covers only `jaeger_query` and `jaeger_mcp` extensions — other components (receivers, processors, exporters) use no-op tracers

##### Architectural context
Jaeger is a *trace backend*, not a trace producer for other backends. Its role in context propagation is:
1. **Accepting** trace context from instrumented services (all major formats)
2. **Storing** and **querying** traces
3. **Self-tracing** its own Query service operations (new in v2, stored internally)

The self-traces do not flow to external OTLP collectors — this is an architectural constraint, not a maturity gap.

#### Checklist assessment

- ✅ W3C Trace Context (traceparent) accepted and honored
- ✅ B3 (Zipkin) format accepted
- ✅ Jaeger format accepted
- ✅ Self-traces use correct span kind (`server`)
- ✅ Self-tracing loop prevention implemented (v2.17.0)
- ✅ `OTEL_TRACES_SAMPLER` supported for controlling self-trace sampling
- ⚠️ Self-traces not exported to external OTLP backends (architectural constraint)
- ⚠️ Span names are URL paths (`/api/traces`) rather than logical operation names (`GetTraces`)
- ⚠️ No parent-child trace relationships visible in self-traces (single-span traces for HTTP calls)
- ⚠️ Trace coverage limited to Query/MCP HTTP operations — storage operations, sampling decisions not traced

#### Rationale

Jaeger's trace modeling is Level 1. It supports all major trace context formats for receiving traces, and has basic self-tracing for its Query service HTTP operations. However, the self-traces are limited to HTTP entry points, do not form multi-span trees, use URL-path span names rather than logical operation names, and are stored internally rather than exported. The architectural role of Jaeger as a terminal trace backend constrains what Level 2 would look like here — but the current state reflects "common paths coherent" with gaps in completeness and export.

---

### 5. Multi-Signal Observability

**Level: 1 — OpenTelemetry-Aligned**

#### Evidence

##### Signal availability
- **Metrics**: First-class ✅ — OTLP push + Prometheus pull, 23 unique metric names (native), covering pipeline throughput, process health, HTTP server performance, batch processor behavior
- **Traces**: Partial ✅ — self-traces stored internally (not exported), covering Query HTTP operations only
- **Logs**: Available but not OTLP-exported ❌ — structured JSON to stdout with OTel resource fields embedded

##### Cross-signal correlation
- **Trace context in logs**: NOT present as OTLP log attributes. Logs are structured JSON to stdout; they do not include `trace_id` or `span_id` as log fields.
- **Shared attributes between metrics and traces**: Both use `service.name=jaeger`, but metrics are exported via OTLP while traces are internal — they cannot be correlated in a shared observability backend.
- **Metrics and traces**: No explicit linkage between metric data points and trace context.

##### Collection model
- Metrics: OTLP push (periodic, 15s interval) + Prometheus scrape (pull, port 8888) — BOTH flowing to eval collector
- Traces: Stored internally in Jaeger's own storage — NOT flowing to eval collector
- Logs: stdout (structured JSON) — NOT flowing via OTLP

#### Checklist assessment

- ✅ Metrics are first-class and well-supported
- ✅ Self-traces exist (limited coverage)
- ✅ Logs are structured with OTel resource fields
- ❌ Logs not exported via OTLP
- ❌ Self-traces not exported to external backends
- ❌ No trace context in logs
- ❌ Cannot pivot from metrics to traces in a shared observability backend
- ⚠️ Signals coexist but cannot be correlated in standard OTel tooling

#### Rationale

Jaeger's multi-signal observability is Level 1. Multiple signals exist, but they are largely independent. Metrics are the only signal that flows to external OTel pipelines. Self-traces are stored in Jaeger's own backend (useful for debugging Jaeger itself, but not part of a unified observability workflow). Logs go to stdout without OTLP export. There is no cross-signal correlation (no trace IDs in logs, no shared backends for metrics and traces). This reflects the architectural reality of Jaeger as a trace *backend* rather than a trace *producer* — but it does mean the project's own observability is fragmented from a user's perspective. Level 2 would require OTLP log export and either external trace export or documented guidance for correlating Jaeger's own telemetry.

---

### 6. Audience & Signal Quality

**Level: 2 — OpenTelemetry-Native**

#### Evidence

##### Span naming
Self-trace span names use URL paths: `/api/traces`, `/api/services`. These are functional and unambiguous for an HTTP API, though they could be improved with HTTP method prefixes (e.g., `GET /api/traces`). The span kind is correctly set to `server`. No internal function names or implementation details appear in span names.

##### Metric quality
The metrics emitted are operationally relevant:
- **Pipeline throughput**: `otelcol_receiver_accepted_spans`, `otelcol_exporter_sent_spans` — directly useful for monitoring Jaeger's processing capacity
- **Health indicators**: `otelcol_receiver_refused_spans`, `otelcol_exporter_send_failed_spans` — actionable for alerting
- **HTTP server performance**: `http.server.request.duration`, `http.server.request.body.size`, `http.server.response.body.size` — useful for Query service SLOs
- **Process health**: `otelcol_process_cpu_seconds`, `otelcol_process_memory_rss` — standard operational metrics
- **Batch processor**: `otelcol_processor_batch_batch_send_size`, `otelcol_processor_batch_timeout_trigger_send` — useful for tuning

The official docs explicitly call out the 4 key metrics to monitor (`otelcol_receiver_accepted_spans`, `otelcol_receiver_refused_spans`, `otelcol_exporter_sent_spans`, `otelcol_exporter_send_failed_spans`), demonstrating user-oriented documentation.

##### Signal-to-noise ratio
- Metrics level is configurable (`basic`, `normal`, `detailed`) — users can tune verbosity
- Default level in production configs is `normal`; `detailed` is used in our evaluation
- No obviously noisy or redundant metrics observed
- Prometheus scrape adds some Prometheus-internal metrics (`scrape_duration_seconds`, `up`, etc.) — these are collector artifacts, not Jaeger-native noise

##### Default usability
- Prometheus monitoring mixin provided: Grafana dashboard (`dashboard-for-grafana.json`) + Prometheus alert rules (`prometheus_alerts.yml`)
- Alert rules in mixin reference v1-era metrics (`jaeger_agent_*`, `jaeger_collector_*`) — these are outdated for v2 and would not fire against v2 deployments. This is a known gap.
- The Grafana dashboard appears to be regenerated using a Go SDK generator (ADR-007 step 2a in v2.17.0 CHANGELOG), suggesting active investment in dashboard quality.
- `OTEL_TRACES_SAMPLER=always_off` documented for disabling self-tracing in production

#### Checklist assessment

- ✅ Metrics defaults are usable without customization
- ✅ Key metrics explicitly documented in monitoring guide
- ✅ Metrics level is configurable
- ✅ Self-trace span kinds correct (`server`)
- ✅ No internal function names in span names
- ✅ Grafana dashboard and Prometheus alerts provided
- ⚠️ Self-trace span names are URL paths (not prefixed with HTTP method)
- ⚠️ Prometheus alert rules in mixin use v1 metric names (outdated for v2)
- ⚠️ Self-tracing coverage is limited (Query + MCP only)

#### Rationale

Jaeger v2's signal quality is Level 2. Metrics are operational, well-documented, and appropriately structured. The project provides tooling (Grafana dashboard, Prometheus alerts) to help operators get started. Self-traces use correct span kinds and current semantic conventions. The main gaps are the outdated alert rules in the monitoring mixin (v1 metric names) and the limited self-tracing coverage. Level 3 would require metrics snapshot testing (partially present in CI), proactive quality regression detection, and updated alert rules for v2.

---

### 7. Stability & Change Management

**Level: 2 — OpenTelemetry-Native**

#### Evidence

##### Documentation of telemetry behavior
- Official monitoring documentation (`/docs/2.17/operations/monitoring/`) explicitly covers:
  - Key metrics to monitor
  - Log verbosity configuration
  - Self-tracing behavior and how to disable it (`OTEL_TRACES_SAMPLER=always_off`)
  - Prometheus scrape configuration
  - Health check endpoints
- The monitoring docs reference OTel Collector documentation for telemetry configuration details — appropriate for an OTel Collector distribution.

##### Change communication
- Telemetry-related changes appear in release notes with PR links:
  - v2.17.0: "Safe self-tracing via otel collector telemetry factory (#8208)" — explicitly called out
  - v2.17.0: "Implement timer duration bucket parsing in metrics init (#7951)"
  - v2.17.0: "Include metric change details in pr comment (#8153)" — CI improvement for tracking metric changes
  - v2.17.0: "Ci: add dashboard sync check for go sdk generator (adr-007 step 3)" — dashboard kept in sync with code
- The CHANGELOG has a CI workflow that compares metric snapshots on PRs, generating a comment with metric changes — this is a proactive mechanism for catching unintended metric changes.

##### Schema URL presence
- `schemaUrl: https://opentelemetry.io/schemas/1.38.0` set on HTTP server metrics ✅
- `schemaUrl: https://opentelemetry.io/schemas/1.18.0` set on OTel Collector internal metrics ✅
- Schema URLs are consistently present on all OTLP metric exports

##### Stability guarantees
- No explicit stability policy for telemetry (e.g., "metrics in this namespace are stable")
- Prometheus monitoring mixin alert rules reference v1 metric names (`jaeger_agent_*`, `jaeger_collector_*`) — these are broken for v2 deployments and have not been updated. This is a gap.
- The Grafana dashboard is regenerated from Go source (ADR-007), suggesting it tracks metric changes.

#### Checklist assessment

- ✅ Telemetry changes documented in release notes with PR references
- ✅ Schema URLs set on all OTLP exports
- ✅ Monitoring documentation explicitly describes telemetry behavior
- ✅ CI metric snapshot comparison on PRs (proactive change detection)
- ✅ Grafana dashboard kept in sync with code generation
- ⚠️ Prometheus alert rules in mixin use outdated v1 metric names (not updated for v2)
- ⚠️ No explicit stability policy distinguishing stable vs experimental telemetry
- ⚠️ Configuration precedence between `service.telemetry.resource` and `OTEL_*` env vars not explicitly documented

#### Rationale

Jaeger v2's stability and change management is Level 2. Telemetry changes are documented in release notes, schema URLs are consistently set, and there is active CI tooling to detect metric changes on PRs. The project treats telemetry as part of its operational surface with dedicated monitoring documentation. The main gaps are the outdated alert rules (v1 metric names still in the mixin), the absence of an explicit stability policy for telemetry, and incomplete migration guidance for users upgrading from v1 to v2. Level 3 would require explicit stability tiers, documented deprecation processes, and updated alert rules for v2.

---

## Key findings

### Strengths

- **Architecture-level OTel commitment**: Jaeger v2 is built on the OTel Collector framework — OpenTelemetry is not bolted on, it is the foundation. This gives it exceptional integration surface maturity.
- **Current semantic conventions**: Self-traces and metrics both use current, non-deprecated OTel semantic conventions (`http.request.method`, `http.response.status_code`, `url.path`, etc.). No deprecated attributes observed.
- **Dual metric export**: Both OTLP push and Prometheus pull are natively supported, allowing integration with both modern OTel pipelines and legacy Prometheus-based stacks.
- **Schema URLs consistently set**: `schemaUrl` is present on all OTLP metric exports, enabling schema-aware tooling.
- **Self-tracing with loop prevention**: v2.17.0 implements safe self-tracing for the Query service with recursive loop prevention — a non-trivial engineering challenge for a trace backend.
- **CI metric change detection**: PR comments include metric change diffs, providing proactive stability monitoring.
- **Stable resource identity**: `service.name`, `service.version`, and `service.instance.id` are consistently set natively across all signals.

### Areas for improvement

- **OTLP log export**: Logs are structured JSON to stdout but not exported via OTLP. Adding OTLP log export would enable cross-signal correlation and complete the multi-signal picture.
- **Self-trace export**: Self-traces are stored internally only. Providing an option to export self-traces to an external OTLP endpoint would enable users to monitor Jaeger's own behavior in their shared observability stack.
- **Update Prometheus alert rules for v2**: The monitoring mixin's `prometheus_alerts.yml` still references v1 metric names (`jaeger_agent_*`, `jaeger_collector_*`, `jaeger_reporter_*`). These alert rules do not work with v2 deployments. This is a significant usability gap for users following the official monitoring guidance.
- **Expand self-tracing coverage**: Self-tracing currently covers only `jaeger_query` and `jaeger_mcp`. Adding tracing for storage operations (e.g., `jaeger_storage` extension) would make Jaeger's own performance more observable.
- **Cross-signal correlation**: Logs do not include trace context (`trace_id`, `span_id`). Adding these fields to structured log output would enable correlation between Jaeger's own log events and its self-traces.

### Notable observations

1. **Jaeger v2 is uniquely positioned**: As an OTel Collector distribution that is also a trace backend, Jaeger occupies an unusual position in the maturity model. It is simultaneously a first-class OTel citizen (built on OTel Collector) and a terminal sink for traces. This means some dimensions (Trace Modeling, Multi-Signal) are constrained by architectural role rather than lack of OTel commitment.

2. **The TODO comment in config is misleading**: The default `config.yaml` still contains `# TODO Initialize telemetry tracer once OTEL released new feature.` — but v2.17.0 actually implements self-tracing (PR #8208). The config comment is outdated and may confuse users.

3. **Metrics snapshot CI is a maturity indicator**: The presence of CI tooling that detects metric changes on PRs (`Include metric change details in pr comment`) indicates the project is treating metrics as a contract worth protecting — this is a Level 2/3 stability practice.

4. **Self-traces use `parentbased_always_on` by default**: This means Jaeger will trace its own Query service requests when they arrive with a `traceparent` header (which is common in OTel-instrumented environments). This is a sensible default that avoids trace noise while enabling observability when needed.

5. **The Grafana dashboard is code-generated**: ADR-007 (referenced in v2.17.0 CHANGELOG) establishes a workflow for generating the Grafana dashboard from Go source using `grafana-foundation-sdk`. This is a sophisticated approach to dashboard lifecycle management that keeps the dashboard in sync with the codebase.

---

## Methodology notes

- Telemetry was collected using an OpenTelemetry Collector (otel/opentelemetry-collector-contrib) with file export in a local kind cluster (`otel-eval-jaeger`)
- The k8sattributes processor was used to distinguish native vs enriched resource attributes — native attributes were identified by comparing OTLP push payloads (which lack k8s attributes) with Prometheus scrape payloads (which gain k8s attributes from the collector)
- Self-traces were observed via the Jaeger Query API (`/api/traces?service=jaeger`) after generating UI traffic
- Semantic conventions were checked against the latest stable OpenTelemetry specification (v1.38.0)
- Documentation was reviewed at `https://www.jaegertracing.io/docs/2.17/operations/monitoring/`
- Source code and release notes were reviewed at `https://github.com/jaegertracing/jaeger`
- The Prometheus monitoring mixin was reviewed at `monitoring/jaeger-mixin/` in the Jaeger repository
