# Dapr - OpenTelemetry Research Notes

## What is Dapr?

Dapr (Distributed Application Runtime) is a CNCF graduated project that provides a portable, event-driven runtime for building distributed applications. It runs as a sidecar alongside application code and provides building blocks for:
- Service invocation (HTTP/gRPC)
- Pub/Sub messaging
- State management
- Bindings (input/output)
- Actors
- Workflows
- Secrets management
- Configuration

## Installation Method

- **Helm chart**: `dapr/dapr` from `https://dapr.github.io/helm-charts/`
- **Latest version**: 1.17.4
- **Namespace**: `dapr-system`
- **Sidecar injection**: via `dapr.io/enabled: "true"` pod annotation

## Telemetry Capabilities

### Traces
- Dapr sidecar (daprd) emits distributed traces for all building block operations
- **OTLP export**: Supported natively via `Configuration` CRD (`spec.tracing.otel`)
- Supported exporters: OTLP, Zipkin, stdout
- Context propagation: W3C Trace Context (traceparent/tracestate headers)
- Sampling rate configurable (0.0 - 1.0)

### Metrics
- Dapr exposes Prometheus metrics on port 9090 (sidecar) and port 9090 (control plane)
- **No native OTLP metrics export** — Prometheus scrape only
- Control plane services expose metrics: dapr-operator, dapr-placement, dapr-sentry, dapr-scheduler
- Sidecar exposes per-app metrics at `<pod-ip>:9090/metrics`
- Key metrics: HTTP/gRPC request counts, latencies, component operation counts

### Logs
- Dapr logs are structured JSON by default (configurable)
- **No native OTLP log export** — logs go to stdout/stderr
- Collected via filelog receiver in OTel Collector
- Log level configurable

## OTel Configuration

Traces are configured via a Dapr `Configuration` CRD:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Configuration
metadata:
  name: dapr-config
  namespace: demo
spec:
  tracing:
    samplingRate: "1"
    otel:
      endpointAddress: "otel-collector-opentelemetry-collector.opentelemetry.svc.cluster.local:4317"
      isSecure: false
      protocol: grpc
```

The annotation `dapr.io/config: "dapr-config"` must be added to application pods to use this configuration.

## Context Propagation

- Dapr uses W3C Trace Context (traceparent/tracestate) for HTTP
- For gRPC, uses grpc-trace-bin and W3C headers
- Dapr automatically propagates trace context between services when using service invocation

## Special Setup Requirements

- Requires CRDs (installed by Helm chart)
- Sidecar injector injects `daprd` container into annotated pods
- `demo` namespace needs `dapr.io/enabled: "true"` annotation on pods
- Metrics scraping requires access to pod IPs (not service IPs)

## Actual Observations (Post-Install)

- TBD
