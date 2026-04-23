# Jaeger Research Notes

## What is Jaeger?
Jaeger is a distributed tracing platform originally developed at Uber and donated to the CNCF. It is used for monitoring and troubleshooting microservices-based distributed systems. Jaeger v2 (2.x) is a major rewrite based on the OpenTelemetry Collector framework — the Jaeger binary is itself an OpenTelemetry Collector distribution.

## Installation
- **Method**: Helm chart from `https://jaegertracing.github.io/helm-charts`
- **Chart**: `jaegertracing/jaeger`
- **Chart version**: 4.7.0
- **App version**: 2.17.0
- **Namespace**: `jaeger`

## Telemetry Produced by Jaeger

### Traces
- Jaeger v2 does NOT self-instrument traces by default (there is a TODO in the config: "Initialize telemetry tracer once OTEL released new feature")
- It receives traces from other services via OTLP (gRPC :4317, HTTP :4318), Jaeger protocol (gRPC :14250, Thrift :14268/:6831/:6832), and Zipkin (:9411)

### Metrics
- Jaeger v2 exposes Prometheus metrics on port **8888** (via OTel Collector telemetry.metrics config)
- Also pushes metrics via OTLP periodic reader to external collectors
- Metrics level can be set to `detailed`, `normal`, or `basic`
- 35 unique metric names including:
  - `otelcol_receiver_accepted_spans`, `otelcol_exporter_sent_spans` (OTel Collector pipeline metrics)
  - `http.server.request.duration`, `http.server.request.body.size` (HTTP server metrics - OTel semantic conventions)
  - `otelcol_process_cpu_seconds`, `otelcol_process_memory_rss` (process metrics)
  - `otelcol_processor_batch_*` (batch processor metrics)
- Resource attributes set natively: `service.name=jaeger`, `service.version=v2.17.0`, `service.instance.id` (UUID)
- NOTE: No Jaeger-specific storage metrics (`jaeger_storage_*`) appeared in this run (only visible with traffic flowing through storage)

### Logs
- Jaeger v2 logs to stdout in structured JSON (zap-based logger)
- Log format: `{"level": "info", "ts": "...", "msg": "...", "resource": {"service.name": "jaeger", "service.version": "v2.17.0", "service.instance.id": "..."}, "otelcol.component.id": "...", "otelcol.component.kind": "..."}`
- No OTLP log export configured by default
- Logs are NOT exported via OTLP (stdout only)

## OpenTelemetry Configuration
- Jaeger v2 IS an OTel Collector distribution — it uses the same config format
- The `userconfig` Helm value overrides the entire config
- Telemetry config is in `service.telemetry` section of the OTel Collector config
- Can configure OTLP metric export in addition to Prometheus pull (both configured in evaluation)
- OTLP push requires `http://` prefix in endpoint URL (not just hostname:port)
- Protocol must be `grpc` not `grpc/protobuf`

## Context Propagation
- Jaeger v2 supports W3C TraceContext (via OTLP receiver) — VERIFIED
- Also supports legacy Jaeger propagation format (via Jaeger receiver)
- Also supports B3 (via Zipkin receiver) — VERIFIED (HTTP 202 response)
- Jaeger does NOT propagate context outbound (it's a backend, not a proxy/middleware)

## Special Setup
- No CRDs required for the non-operator chart
- Uses in-memory storage by default (good for evaluation)
- Ports exposed:
  - 4317: OTLP gRPC
  - 4318: OTLP HTTP
  - 16686: Jaeger UI / Query HTTP
  - 14250: Jaeger gRPC (legacy)
  - 14268: Jaeger Thrift HTTP (legacy)
  - 9411: Zipkin
  - 8888: Prometheus metrics
  - 13133: Health check

## Key Observations
- Jaeger v2 is built ON TOP of OpenTelemetry Collector — it's a first-class OTel consumer
- Self-metrics are OTel Collector internal metrics exposed via both Prometheus pull AND OTLP push
- No native self-tracing (traces of Jaeger's own operations) — TODO comment in upstream config
- The project itself is a telemetry BACKEND, not a telemetry PRODUCER in the traditional sense
- Metrics use OTel semantic conventions for HTTP (`http.server.request.duration`, `http.server.request.body.size`)

## Actual Telemetry Flowing (Post-Installation)

### Metrics — FLOWING ✅ (DUAL: Prometheus scrape + OTLP push)
- **Prometheus scrape** (port 8888): 35 unique metric names
- **OTLP push** (periodic reader to eval collector): Same metrics in OTLP format
- Both channels confirmed flowing to `/tmp/otel-eval-jaeger/metrics.jsonl`
- Resource attributes (native): `service.name=jaeger`, `service.version=v2.17.0`, `service.instance.id` (UUID)
- Prometheus annotations on pod: `prometheus.io/scrape=true`, `prometheus.io/port=8888`

### Traces — NOT FLOWING ❌ (by design)
- Jaeger receives traces from services (OTLP, Jaeger, Zipkin protocols) but stores them internally
- Does NOT export its own traces to other backends
- No self-tracing of Jaeger's own operations (TODO in upstream config)
- Jaeger IS a trace backend, not a trace producer

### Logs — NOT FLOWING via OTLP ❌ (stdout only)
- Jaeger logs to stdout in structured JSON (zap-based logger)
- Logs are NOT exported via OTLP
- Log format IS structured JSON with OTel resource fields embedded
- Filelog receiver could not capture Jaeger container logs (kind volume issue)

### Context Propagation
- Jaeger v2 accepts W3C TraceContext (OTLP receiver) — VERIFIED
- Jaeger v2 accepts Zipkin B3 format — VERIFIED (HTTP 202)
- Jaeger v2 accepts Jaeger Thrift/gRPC format — VERIFIED (port open, 405 on GET is expected)
- Jaeger does NOT propagate context outbound (it's a backend, not a proxy)

### Notable Configuration Issues
1. `tls` key not supported in OTLP exporter config for telemetry metrics readers
2. `grpc/protobuf` protocol not supported — must use `grpc`
3. `ui.config_file` in `jaeger_query` extension causes panic if file doesn't exist
4. OTLP push of self-metrics works with `http://` prefix in endpoint URL
