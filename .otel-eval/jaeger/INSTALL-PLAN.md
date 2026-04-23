# Jaeger Installation Plan

## Installation Method
- **Helm chart**: `jaegertracing/jaeger` v4.7.0 (app v2.17.0)
- **Namespace**: `jaeger`
- **Storage**: In-memory (max 100,000 traces)

## Telemetry Configuration
Jaeger v2 is an OTel Collector distribution. We configure it to:
1. Expose Prometheus metrics on port 8888 (default, already in config)
2. Also push metrics via OTLP to our evaluation collector
3. Enable detailed metrics level

## Collector Changes Needed
- Add Prometheus scrape job for Jaeger's metrics endpoint (port 8888)
- Jaeger will also push OTLP metrics to the collector

## Routing/Ingress Setup
- Port-forward Jaeger's OTLP gRPC port (4317) and HTTP port (4318) for traffic generation
- Port-forward Jaeger UI (16686) for verification

## Traffic Generation
- Send OTLP traces to Jaeger directly (Jaeger is a trace backend, so we send traces TO it)
- Use `curl` with OTLP HTTP to push trace data
- This exercises Jaeger's receiver pipeline

## Versions
- Chart: 4.7.0
- App: 2.17.0
