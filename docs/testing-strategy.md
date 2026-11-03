# Module 3 testing strategy

The goal is confidence in the recording lifecycle: accept a valid MP3, preserve
its bytes, process its notification, save the extracted metadata, and delete
the recording and metadata. Failures should remain observable and retryable
according to the existing Module 2 behavior.

## Coverage approach

Use a testing pyramid: many fast unit checks, focused tests of real integration
boundaries, isolated business scenarios, compatibility checks between services,
and a small set of full-stack API scenarios. Each layer finds different faults;
100% unit coverage would not prove that SQL schemas, S3 requests, RabbitMQ
routing, discovery or deployed HTTP interactions work together. There is no
arbitrary line-coverage percentage target. Every selected business scenario and
every inter-service contract in those scenarios must have an executable check.

| Layer | Responsibility | Infrastructure and isolation |
| --- | --- | --- |
| Unit | Validation boundaries, service decisions, metadata extraction, retries, publisher behavior | JUnit Jupiter and Mockito; no external services |
| Integration | Song persistence using the production SQL schema; real S3 put/get/delete; Resource deletion transactions; broker retry/rejection behavior | Disposable PostgreSQL, LocalStack and RabbitMQ Testcontainers; other application services mocked |
| Component | Complete Processor behavior for a valid recording, identical duplicate, and deleted recording | Cucumber; real listener, extraction, clients and RabbitMQ; Resource/Song HTTP providers replaced by generated stubs |
| Contract | Compatibility at both ends of each selected HTTP/message interaction | Spring Cloud Contract generated provider tests; versioned stubs loaded by real consumer clients/listener |
| E2E | Public API flow through the gateway with all services participating | Cucumber plus a disposable Docker Compose project; no application-service mocks |

## Scenarios and expected outcomes

Unit tests cover valid metadata, duration/year/ID boundaries, required fields,
CSV validation, creation, conflicts, missing songs and selective deletion.
Existing Resource/Processor/Gateway tests remain part of the fast suite.

Integration tests verify that complete song metadata survives a real database
round trip, duplicate creation does not overwrite it, and deletion commits.
S3 tests store the checked-in MP3 fixture, compare every downloaded byte, verify
the content type and confirm idempotent object deletion. Existing SQL rollback
and broker retry/dead-letter scenarios run in the integration profile.

Processor component scenarios are executable natural-language specifications in
`resource-processor/src/test/resources/features/component/resource-processing.feature`.
They require metadata creation for a valid recording, successful comparison for
an identical duplicate and no Song request for an already deleted resource.
Broker acknowledgement and absence of a dead-letter message distinguish
successful processing from a request that merely started.

E2E scenarios are in `e2e-tests/src/test/resources/features/e2e/resource-lifecycle.feature`:

1. Upload an MP3 through the gateway, await all expected metadata fields within
   60 seconds, retrieve identical bytes/content headers, delete both records,
   and confirm a repeated deletion returns an empty list.
2. Reject an unsupported content type with HTTP 400.
3. Reject invalid MP3 bytes even when the content type claims `audio/mpeg`.
4. Reject invalid Resource and Song identifiers with the API error envelope.
5. Reject Song metadata missing required fields.

## Contract ownership and stub propagation

Song Service owns contracts for successful creation, invalid metadata, duplicate
conflicts, metadata lookup (including duplicate comparison), missing metadata,
and deletion of existing/missing metadata. Resource Service owns contracts for
binary downloads and missing resources, plus its upload notification:

- Exchange: `resources`; routing key: `resource.uploaded`.
- JSON body: `{"resourceId":42}`, representing a positive integral identifier.
- Content type: `application/json`; producer verification also checks persistent delivery.

Provider tests exercise the real controllers/error mapping and the real event
publisher. Business persistence is isolated in HTTP provider tests; database
correctness belongs to integration tests. The generated message test uses real
RabbitMQ, and an independent queue binding checks the expected exchange/key.

Build providers in dependency order: Song Service, Resource Service, Processor.
The provider build generates `*-1.0.0-stubs.jar`; `mvn install` propagates that
artifact to the local Maven repository. Consumer tests load the exact
`com.example.microservices:<provider>:1.0.0:stubs` artifact in LOCAL mode.
The Resource consumer exercises Song deletion; the Processor consumers exercise
binary download, creation, conflicts, validation failure and the message
listener. Cucumber component tests reuse those same HTTP stubs. No separate
handwritten HTTP stub definitions or remote artifact publishing are required.

## Running the suites

Prerequisites: Docker Desktop running Linux containers, PowerShell and network
access for the first Maven/image downloads. The runner uses Maven with Java 17
in Docker, compiles release-17 sources and caches dependencies in the user's
local Maven repository. LocalStack uses the same pinned version as the existing
application (Community 4.14.0); no activation token is required. Automated test
containers use their own configuration rather than loading the development `.env`.

From the repository root:

```powershell
./scripts/test.ps1 -Suite Unit
./scripts/test.ps1 -Suite Integration
./scripts/test.ps1 -Suite Contracts
./scripts/test.ps1 -Suite Component
./scripts/test.ps1 -Suite E2E
./scripts/test.ps1 -Suite All
```

`Contracts` and `Component` install the required provider stubs first. `All`
combines provider verification/installation with integration tests, then runs
Processor integration/contract/component tests, existing Gateway/Eureka checks,
and E2E. Maven's default `test` runs the fast tests; `integration`, `contracts`
and `component` profiles select the other service suites during `verify`.

For an existing isolated stack, E2E can also run with Java 17+ and Maven:

```powershell
mvn -f e2e-tests/pom.xml -Pe2e -De2e.base-url=http://localhost:PORT verify
```

The script creates a unique Compose project with separate databases, broker,
storage and discovery. Only the gateway has a host port, allocated dynamically.
It tears down that project in a `finally` block and prints service logs on
failure. E2E hooks clean up only IDs uploaded by the current scenario. Readiness
and asynchronous results use bounded polling. No fixed startup sleep or blanket
deletion of development data is used.

## Evidence and acceptance

Surefire writes fast-test results under each module's `target/surefire-reports`.
Failsafe writes the selected service suites to `target/integration-reports`,
`target/contracts-reports` and `target/component-reports`, with separate summary
files. E2E uses `e2e-tests/target/failsafe-reports`. Cucumber also produces JSON
and HTML reports under the corresponding module's `target` directory.

A requested slow suite fails when no tests are discovered. Infrastructure
failures and skipped tests do not satisfy the assignment's execution requirement.
Completion requires at least one created and passed test in every category,
plus a green run of the existing suites. Actual results are recorded separately
in `docs/module-3-test-results.md` after execution.
