# Module 7 Monitoring Implementation Plan

Implement with regression tests and one independent final review.

**Goal:** Implement README (9).md as a stacked PR without changing PR #6.
**Architecture:** JSON files -> Fluent Bit -> Elasticsearch; Prometheus -> Grafana; X-Trace-ID through Gateway, servlet services, HTTP clients and RabbitMQ.
**Tech Stack:** Existing Java 17 / Boot 3.5.16 / Cloud 2025.0.3, Boot structured logging, Micrometer Prometheus, Elasticsearch, Fluent Bit, Prometheus, Grafana.
**Spec:** docs/specs/2026-11-25-module-7-monitoring-design.md.

## Global constraints
- Branch feature/module-7-monitoring from exact PR #6 head; new PR targets feature/module-6-fault-tolerance. Never merge/close/update PR #6.
- Preserve original dirty checkout, existing APIs/event bodies, retry budgets, promotion safety and backend replica support.
- Keep observability failures outside business-service health dependencies; use only disposable owned verification resources.
- No tool branding in branch, commits, changed file content or PR text.

## Review focus
1. MDC leaks on reused HTTP/listener threads or errors; test prior context restoration.
2. Reactor concurrency sharing IDs; test simultaneous requests on switched schedulers.
3. Retry and legacy AMQP events breaking correlation; test headers through both hops without payload changes.
4. Missing service replicas or empty but healthy scrape endpoints; prove real JVM and Gateway series.
5. Apparently working logs that vanish on restart; prove indexed upload trace and persisted records after storage/collector restart.

## Task 1: HTTP and message correlation
**Files:** observability/TraceContext.java and TraceCorrelationFilter.java under each servlet service; Gateway TraceCorrelationWebFilter.java; Resource/Processor HTTP configurations, both publishers and listeners; tests alongside each behavior.
**Interfaces:** X-Trace-ID header, MDC traceId, TraceContext.open(Object):AutoCloseable scope with id(), currentOrCreate():String. IDs validate ASCII [A-Za-z0-9][A-Za-z0-9_-]{0,63}; generated UUID hexadecimal. Scope restores only its previous MDC value.
- [x] Write and run failing filter/context tests for missing/valid/invalid IDs, exception restoration, and concurrent Gateway requests.
- [x] Implement HTTP filters and HTTP propagation; run affected fast suites.
- [x] Write and run failing real publisher/listener tests for both header hops, scope/failure restoration and unchanged message bodies; implement and verify.

## Task 2: Metrics and structured logs
**Files:** six application.properties/pom.xml files; Gateway Prometheus endpoint test; observability logging tests.
**Interfaces:** JSON service/traceId fields; /actuator/prometheus; jvm_memory_used_bytes, jvm_threads_live_threads, jvm_gc_pause_seconds_count and spring_cloud_gateway_requests_seconds_*.
- [x] Add failing endpoint and JSON correlation assertions; enable Boot structured logging, bounded rotation and managed Prometheus registry.
- [x] Enable Gateway metrics and histograms without high-cardinality path/trace labels. Run focused checks.

## Task 3: Persistent container stack and dashboards
**Files:** compose.yaml, compose.monitoring-testing.yaml, observability/fluent-bit/*, observability/prometheus/prometheus.yml, observability/grafana/provisioning/*, observability/grafana/dashboards/*.json, .env.example.
**Interfaces:** service JSON files in per-container paths; Elasticsearch index microservices-logs; Grafana datasource UIDs prometheus/elasticsearch, dashboard UIDs microservices-overview/upload-trace; traceId textbox Lucene lookup.
- [x] Configure collector offsets/filesystem buffer and named data volumes; pin/configure images and loopback ports; configure scrape discovery for all replicas.
- [x] Provision JVM, GC, threads, Gateway throughput/latency/errors and trace-correlated logs dashboards with automatic refresh.
- [x] Validate normal/development/testing Compose combinations and provisioned configs against real containers.

## Task 4: Live proof and delivery
**Files:** scripts/verify-module-7.ps1, docs/module-7-verification.md, README.md, docs/testing-strategy.md.
**Interfaces:** disposable project; results.json with recorded checks and trace ID; random host ports; only owned resources cleaned in finally.
- [x] Upload with a known ID and one generated ID; verify complete promotion and same trace across HTTP + both message hops using Elasticsearch and Grafana datasource API.
- [x] Prove all six Prometheus targets and real JVM/Gateway series, dashboard provisioning, concurrent IDs, persistent logs across collector/store restart and upload with collector offline.
- [x] Run full existing suites once, one final review and necessary fixes; record actual counts and limitations.
- [x] Commit/push only Module 7 changes and create stacked PR; verify PR #6 remains open, unchanged and unmerged.
