# Module 7: Logging, metrics and tracing

This module branches from `feature/module-6-fault-tolerance` (PR #6). The Module 7 PR targets that branch; it does not merge or close the existing PR.

## Start the application and monitoring stack

From the repository root in PowerShell 7 with Docker Desktop running:

```powershell
docker compose up --build -d --scale song-service=2
```

Use `.env.example` for configuration. The normal Compose stack includes Elasticsearch, Fluent Bit, Prometheus and Grafana. Services write Boot-native Logstash JSON; a persistent shared volume gives every service/container its own file. Fluent Bit persists tail offsets and a filesystem retry buffer, and indexes records in Elasticsearch's `microservices-logs` index. Business services do not depend on collector/store health.

| Tool | Default local URL | Purpose |
| --- | --- | --- |
| Gateway | http://localhost:8082 | Existing APIs and `/actuator/prometheus` |
| Grafana | http://localhost:3000 | Provisioned JVM/Gateway and trace-log dashboards |
| Prometheus | http://localhost:9090 | Targets and PromQL queries |
| Elasticsearch | http://localhost:9200 | Indexed application logs |

Monitoring ports bind to loopback. Grafana's development defaults are `admin` / `monitoring`; configure `GRAFANA_USER` / `GRAFANA_PASSWORD` in `.env` as needed. Business backend ports remain internal unless `compose.development.yaml` is added.

In Grafana, open the **Microservices** folder:

- **Microservices - JVM and Gateway**: heap/non-heap usage, GC pause time, live threads, Gateway request count/rate, p95 latency and 5xx fraction. Refresh is five seconds; GC graphs populate when collections occur.
- **Microservices - Logs by trace ID**: paste the upload response's `X-Trace-ID` into the textbox. It queries all services through the provisioned Elasticsearch data source. Adjust the time range for older uploads.

Prometheus uses DNS discovery to scrape all six applications, including every Song replica, at `/actuator/prometheus`. Gateway metrics use `spring_cloud_gateway_requests_seconds_*`; metric labels exclude trace IDs and raw paths.

## Upload and follow one trace

```powershell
$trace='upload-'+[Guid]::NewGuid().ToString('N')
$response=Invoke-WebRequest http://localhost:8082/resources `
  -Method Post -ContentType audio/mpeg `
  -Headers @{'X-Trace-ID'=$trace} `
  -InFile ./resource-service/src/test/resources/mp3/valid.mp3
$response.Headers['X-Trace-ID']
$response.Content
```

The Gateway returns the ID and propagates it to downstream HTTP calls. Omit the header to generate an ID. Valid IDs are 1-64 ASCII characters: an alphanumeric first character followed by alphanumeric, underscore or hyphen; invalid values are replaced with a generated 32-character hexadecimal ID. These IDs correlate logs; they are not authentication credentials.

The same ID crosses the `resource.uploaded` and `resource.processed` RabbitMQ headers while the JSON bodies stay unchanged. Listeners establish a scoped MDC value, include processing/failure logs, and restore the previous context on success or failure. Legacy messages without headers get a generated ID. Gateway binds MDC only during log emission, rather than leaving thread-local state across reactive work.

The trace search finds Gateway upload, Resource upload/download/promotion, Processor processing/publication, Song metadata and Storage catalog requests. An equivalent Elasticsearch query is:

```json
{"query":{"term":{"traceId.keyword":"YOUR_TRACE_ID"}}}
```

## Verification

```powershell
./scripts/test.ps1 -Suite All
./scripts/verify-module-7.ps1
```

Other automated selections remain `Unit`, `Integration`, `Contracts`, `Component` and `E2E`. The Unit selection now includes Eureka's tracing tests. Suites run in Docker Maven / Java 17.

The live runner builds a unique disposable stack, uses random loopback host ports, starts two Song replicas and records:

- All six healthy Prometheus jobs, both Song targets, actual JVM series and Gateway counters/histograms.
- Separate per-container Song log files, both provisioned Grafana dashboards and working log/metric queries through Grafana's data-source API.
- A known upload ID and a generated ID correlated across HTTP and both message hops, including the final Resource completion log; completed promotion and exact download SHA-256.
- Eight concurrent Gateway requests whose returned IDs and indexed Gateway/Storage logs remain distinct.
- A successful recording while collector and Elasticsearch are stopped; persisted original logs after replacing the Elasticsearch container without deleting its volume; actual size rollover while offline; collector offset recovery/catch-up without replaying consumed trace records.

Only the generated project's containers and volumes are removed in cleanup. Evidence stays under `output/module-7/<project>/results.json`, trace query snapshots and `services.log`. `-SkipBuild` skips forced image rebuilding; Compose can still build images missing for a new disposable project. These recorded live assertions are separate from Maven test counts.

## Persistence and boundaries

Normal Compose uses named volumes for Elasticsearch records, Fluent Bit state/buffer, application files, Prometheus and Grafana. `docker compose down` retains these volumes; `down --volumes` removes the stored data. Application files rotate at 10 MB into uncompressed `.rotated` archives with seven history periods and a 100 MB per-file-set cap. Fluent Bit discovers active and retained rotated files and keeps inode-based offsets; recovery is limited by file retention and buffer capacity. The disposable test uses a 10 KB Gateway limit to force rotation with real traffic. Prometheus retains seven days; Elasticsearch index retention is operator-managed. Collector buffering is bounded at 100 MB, so an unlimited logging outage is not a lossless-storage guarantee.

This is trace-ID correlation, not a distributed-span backend. Existing at-least-once delivery, upload commit-to-publish window, promotion retry and DLQ replay behavior from Module 6 remain. No resource database migration is added by Module 7; Module 6's migration remains necessary for older databases.

## Configuration references

- [Spring Boot 3.5 structured logging](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html)
- [Spring Boot test observability](https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html)
- [Fluent Bit Elasticsearch output](https://docs.fluentbit.io/manual/4.0/data-pipeline/outputs/elasticsearch)
- [Prometheus DNS service discovery](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#dns_sd_config)
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)
