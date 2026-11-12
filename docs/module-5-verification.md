# Module 5 service discovery verification

Run from the repository root with PowerShell 7 and Docker Desktop:

```powershell
./scripts/verify-module-5.ps1 -Mode Docker
./scripts/verify-module-5.ps1 -Mode Local
./scripts/test.ps1 -Suite All
```

Local mode also requires Java on PATH. Maven builds and E2E checks use Docker
Java 17; local application processes use the installed host Java runtime. Each
verification command creates its own disposable Compose project and cleans up
only that project and the Java processes it started. Generated reports and local
application logs live under `output/module-5/<project>/`.

The current Windows host rejects Java NIO's default Unix-domain socket connection
with `Unable to establish loopback connection` / `Invalid argument: connect`.
A standalone `Selector.open()` probe reproduced it under Java 21 and 26. For this
host, the local verification command uses a process-local socket temporary-path
override; the nonexistent directory makes the JDK fall back to TCP loopback:

```powershell
$socketFallback = Join-Path $PWD 'output/module-5/nonexistent-socket-directory'
./scripts/verify-module-5.ps1 -Mode Local -JavaOptions "-Djdk.net.unixdomain.tmpdir=$socketFallback"
```

Leave that fallback directory nonexistent. The runner applies these optional JVM
arguments only to its owned Java processes; no system Java settings are changed.

## Assertions

- Gateway and Resource Service are registered UP in Eureka.
- Docker mode requires two distinct UP Song registrations and proves actual
  Gateway traffic reached both containers using test-only access logs.
- Business requests go through the Gateway; downstream 404 envelopes and the
  full recording lifecycle retain their existing API behavior.
- Undefined routes return a JSON 404 with `Route not found`.
- A stale registered Song address and an empty Song registry return JSON 503
  with `Service unavailable`.
- Docker mode verifies continued routing after one replica stops. Both modes
  verify registration and routing recovery after Song Service restarts.
- Fast Gateway tests additionally cover upstream response timeout (504),
  unexpected failures (500), safe framework status preservation, committed
  responses, Actuator health, binary forwarding and DELETE query preservation.

The Processor consumes discovery without registering a business API. Config
Server is optional in the assignment and is outside this implementation.

## Execution results — 12 November 2026

| Verification | Result | Evidence |
| --- | --- | --- |
| Docker discovery run, after review fixes | Passed all 9 checks, including two-instance traffic, failure modes and recovery; 6 E2E scenarios passed | `output/module-5/microservices-discovery-27606227/results.json` |
| Local Java run with the socket-temp override | Passed all 8 checks, including registration, routing, failure modes and recovery; 6 E2E scenarios passed | `output/module-5/microservices-discovery-2a5f92e5/results.json` and application logs alongside it |
| Normal and explicit development Compose configuration | Passed; normal backend ports are unpublished and development host ports are present | `docker compose config --quiet` and the equivalent two-file development command |
| Full regression suite | Passed: 153 tests, 0 failures, 0 errors, 0 skips | `./scripts/test.ps1 -Suite All`; `output/module-5/full-suite-summary.json` |

Full-suite counts from the fresh JUnit XML reports:

| Module | Tests | Failures / errors / skips |
| --- | ---: | --- |
| Song Service | 40 | 0 / 0 / 0 |
| Resource Service | 48 | 0 / 0 / 0 |
| Resource Processor | 48 | 0 / 0 / 0 |
| API Gateway | 11 | 0 / 0 / 0 |
| Full-stack E2E | 6 | 0 / 0 / 0 |
| Total | 153 | 0 / 0 / 0 |

Eureka Server also built successfully in the full runner; it has no separate
JUnit tests. Gateway counts include the 405 `Allow` regression after its fix.

Docker builds/tests use Temurin Java 17; the full-suite runtime reports
`17.0.20.1`. Local application processes ran on the installed Oracle Java
`26.0.2.1`. Docker Engine reports `29.7.2`.

Both successful discovery runs completed cleanup of their owned resources. The
initial local run without the JVM workaround failed during Eureka's NIO listener
startup, before any local acceptance checks could pass; it is not counted as a
successful run.

The independent code review identified three issues, all addressed: preserve the
framework `Allow` header on 405 errors, always attempt Docker teardown after Java
cleanup failures, and require Gateway-side empty-instance evidence instead of
accepting any 503 after deleting server registry entries. The 405 regression test
failed before the header fix. A cleanup fault-injection check reproduced skipped
teardown before the fix and passed afterwards; the empty-discovery assertion
rejects a stale-connection-only 503 and passes with the load balancer's empty-list
log evidence. The final Docker and local runs exercised the strengthened checks.
