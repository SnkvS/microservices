# Module 7 monitoring design

Assignment: Downloads/README (9).md. User authorized implementation and a new PR on 2026-11-25. Base: PR #6, feature/module-6-fault-tolerance, 3b010802b4a9b19667c3e93613997e26a42d87a8. Existing PR must remain open and unmodified.

## Scope and implementation choices

Add indexed persistent application logs, service metrics, provisioned JVM/Gateway dashboards and a trace-ID log search. Preserve Java 17, Boot 3.5.16, Cloud 2025.0.3, current APIs, event bodies, processing guarantees and backend scaling. Reuse the clean existing working folder; leave the original developer checkout untouched. Branch: feature/module-7-monitoring. New PR base is feature/module-6-fault-tolerance.

Builder choice: Elasticsearch + Fluent Bit + Prometheus + Grafana. Elasticsearch is one of the assignment's listed stores; Grafana can query both logs and metrics, avoiding an extra Kibana container. Loki is lighter but is not among the suggested stores; a full Logstash/Kibana stack adds unnecessary services. Containers and image versions are pinned and configurable. No external SaaS, tracing backend or dependency/platform upgrade is required.

## Logs and correlation

All six Spring applications write Boot-native Logstash JSON with a service field. Compose mounts a shared application-log volume with per-service/per-container filenames; Fluent Bit tails it into an indexed Elasticsearch store. Elasticsearch data, collector offsets/buffer, Grafana and Prometheus data use named volumes. Log rotation is bounded and uncompressed; the collector discovers active files and retained archives with persisted inode offsets. Collector retries do not make business health depend on the observability stack.

X-Trace-ID is generated at Gateway ingress if missing/invalid, returned to the caller and propagated to downstream HTTP requests and both RabbitMQ event headers. Accept bounded ASCII IDs (1-64 characters: alphanumeric followed by alphanumeric, underscore or hyphen), otherwise generate 32 hexadecimal characters. These are correlation identifiers, not authentication credentials or metric labels. Existing JSON event bodies remain unchanged.

Servlet filters establish and restore MDC in try/finally; HTTP client interceptors propagate the current ID. Message listeners establish one scope around validation, processing and failure logging, restore the previous thread context even on exceptions, and generate an ID for legacy messages. Publishers retain one ID across retries. Gateway uses request-local immutable state and binds/restores MDC only during synchronous log emission, never across reactive work. Every application logs a request or processing boundary so a full upload trace can be queried.

## Metrics and visualization

Add the Boot-managed Prometheus registry and expose health and prometheus on every service. Gateway's /actuator/prometheus is the requested public metrics endpoint; internal services stay internal in normal Compose. Explicitly enable Gateway request metrics and histograms. Prometheus DNS discovery scrapes each replica of all six services. No trace IDs, resource IDs or raw paths become metric labels.

Provision Grafana data sources and two refreshable dashboards: JVM/Gateway health (heap/non-heap, GC, threads, traffic, p95 latency, error rate) and logs filtered by trace ID. Local development Grafana access uses configured credentials. Monitoring data ports bind to loopback; application operation remains independent of collector/store availability.

## Verification

Behavioral tests first: valid/invalid/missing IDs, HTTP forwarding, concurrent/reactive requests, MDC restoration on failure, both AMQP hops/retry identity and old-message compatibility. Preserve contract payloads. Test actual Prometheus scrape series for all services/replicas, Grafana provisioned dashboards/data-source queries and an upload whose one ID finds Gateway, Resource, Processor, Song and Storage logs through Elasticsearch. Restart logging storage/collector without deleting volumes to prove persistence and continue business processing. Disposable owned Compose projects only; do not touch existing containers/data.

Deliver a stacked PR with only Module 7 changes, documented run/test/trace-query instructions and honest test evidence. Do not merge or close PR #6. No PDF is part of this request.
