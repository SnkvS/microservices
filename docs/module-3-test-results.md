# Module 3 test execution results

Date: 2026-10-30. Branch: `feature/module-3-testing`.

Verified with `./scripts/test.ps1 -Suite All` using Docker Desktop Linux
containers, Maven 3.9, Java 17.0.20.1 and release-17 compilation.
Integration infrastructure: Testcontainers 1.21.4, PostgreSQL 16,
RabbitMQ 4.1 and LocalStack 2026.08.0.

## Results

| Module | Fast tests | Integration | Contract | Component | E2E |
| --- | ---: | ---: | ---: | ---: | ---: |
| Song Service | 27 | 3 | 10 | - | - |
| Resource Service | 38 | 5 | 5 | - | - |
| Resource Processor | 37 | 5 | 3 | 3 | - |
| API Gateway | 2 | - | - | - | - |
| E2E module | - | - | - | - | 6 |
| **Total** | **104** | **13** | **18** | **3** | **6** |

**144 passed checks; zero failures, errors or skips.** Fast tests include unit
tests and existing isolated HTTP/application checks. Eureka's Maven test
lifecycle succeeds but has no test sources, so it is not counted as evidence
for any category.

## Coverage added

- Unit: 27 Song validation/service checks and six independently varied
  metadata-conflict cases in Processor HTTP client tests.
- Integration: three Song/PostgreSQL scenarios and a real S3 storage lifecycle
  check. Existing Resource transaction and Processor broker checks are retained.
- Contract: ten generated Song HTTP checks, three Resource HTTP checks, one
  real message-publisher check, one Song-delete consumer check and three
  Processor HTTP/message consumer checks.
- Component: three Cucumber scenarios for valid recording processing,
  identical duplicate delivery and an already deleted resource.
- E2E: six Cucumber API scenarios through the gateway with all services running,
  covering the complete lifecycle, exact MP3 bytes/metadata and invalid input.

Provider builds generate and locally install
`song-service-1.0.0-stubs.jar` and `resource-service-1.0.0-stubs.jar`.
Consumer tests resolve those exact artifacts in LOCAL mode. The E2E runner
removes its unique Compose project and disposable containers/network afterward.

## Reproduce and inspect

```powershell
./scripts/test.ps1 -Suite All
```

Individual suite commands and report paths are described in
[`testing-strategy.md`](testing-strategy.md). JUnit XML and Cucumber JSON/HTML
reports are generated under the module `target` directories. The full local
pre-publication run log is `output/module-3-pre-pr-all.log`; generated reports
and logs are intentionally excluded from version control.

The six metadata conflict cases were also checked with a controlled mutation:
replacing full-record comparison with title-only comparison made five cases
fail; restoring the original implementation made all six pass. Production
sources and public API behavior are unchanged.

Native Windows/JDK 21 execution is not claimed as verified: an initial existing
Processor discovery test encountered `IOException: Unable to establish loopback
connection`. The verification results above use Java 17 in Docker.
