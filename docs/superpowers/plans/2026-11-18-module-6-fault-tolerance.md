# Module 6 Fault Tolerance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task, according to the user's selected execution method. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement the specified Storage API, STAGING-to-PERMANENT processing and Resilience4j fallback while preserving existing Resource/Song contracts.

**Architecture:** A new Storage Service owns location definitions. Resource Service resolves definitions through a circuit breaker and persists actual locations plus promotion recovery information. Processor publishes a confirmed completion event after metadata saving; Resource Service consumes it idempotently and safely completes the S3 move.

**Tech Stack:** Existing Java 17 / Spring Boot 3.5.16 / Spring Cloud 2025.0.3, PostgreSQL, RabbitMQ, AWS SDK S3, LocalStack, JUnit/Testcontainers, Spring Cloud Contract and Cucumber; add only a compatible Resilience4j dependency.

**Spec:** `docs/superpowers/specs/2026-11-18-module-6-fault-tolerance-design.md`. Assignment: `C:/Users/SerhiiSenkiv/Downloads/README (8).md`.

## Global constraints

- User-confirmed selection: multiple entries per type are allowed; select the lowest-ID entry of the requested type.
- Keep Java 17, Spring Boot 3.5.16 and Spring Cloud 2025.0.3; no unrelated dependency upgrades.
- Preserve existing Resource/Song public endpoints, response envelopes, binary behavior and `resource.uploaded` payload.
- Storage definitions belong to Storage Service; resource state and actual locations belong to Resource Service.
- No new PUT/PATCH, Storage GET-by-ID or public Resource state endpoint; no general Saga/outbox framework or automatic DLQ replay.
- No destructive database/bucket reset. Preserve legacy physical locations and unrelated dirty work.
- The user approved implementation on 2026-11-18 and commit/PR delivery on 2026-11-19. Work is isolated on feature/module-6-fault-tolerance.

## Review focus

1. A copied object followed by failed DB commit must leave the original readable and allow retry at the same target (Task 3).
2. Source deletion failure after PERMANENT commit must remain recoverable on redelivery/restart (Tasks 3/4).
3. A late completion racing DELETE must not recreate a deleted recording; concurrent downloads remain readable, and identical source/target locations must never delete the authoritative object (Task 3).
4. Resource Service must start with Storage absent and use fallback without a warm cache or Compose health dependency (Tasks 2/5/6).
5. An invalid successful Storage response is not an outage and must not silently route writes through stub data (Task 2).

## Execution setup

- [x] Confirm the current branch, dirty-file snapshot and remote base. Start a separate `feature/module-6-fault-tolerance` branch when implementation is authorized; preserve local docs/sample files and `.gitignore`.
- [x] Save a plan-specific execution ledger and baseline verification results. Use Docker Java 17 for executable test evidence; keep the Windows local JVM workaround separately documented.
- [x] For each task, write its behavioral tests first, observe the expected failures, implement, and run the stated checks. Resolve environment/setup errors before calling a failure a valid red test.

## Task 1: Storage Service API, persistence and bootstrap

**Create:** `storage-service/pom.xml`, `storage-service/Dockerfile`, `storage-service/.dockerignore`, `storage-service/src/main/resources/application.properties`; Java files under `storage-service/src/main/java/com/example/storage/`: `StorageApplication.java`, `api/StorageController.java`, `api/StorageDto.java`, `api/CreateStorageRequest.java`, `api/StorageErrorHandler.java`, `service/StorageService.java`, `service/StorageValidation.java`, `service/StorageInitializer.java`, `persistence/StorageEntity.java`, `persistence/StorageRepository.java`, `model/StorageType.java`; `init-scripts/storage-db/init.sql`.

**Tests/contracts:** `storage-service/src/test/java/com/example/storage/api/StorageControllerTest.java`, `service/StorageServiceTest.java`, `service/StorageValidationTest.java`, `persistence/StoragePersistenceIT.java`, `contract/HttpContractBase.java`; `storage-service/src/test/resources/contracts/http/{create_storage,list_storages,delete_storages,invalid_storage}.groovy`.

**Interfaces:** `StorageDto(Long id, StorageType storageType, String bucket, String path)`; `CreateStorageRequest(StorageType storageType, String bucket, String path)`; `StorageService.create(request):long`, `list():List<StorageDto>`, `delete(rawIds):List<Long>`. HTTP contracts are the design's exact three endpoints; expose no database entities directly.

- [x] Test required/type/bucket/path validation, canonical trailing-slash normalization and duplicate location rejection. Assert POST 200 `{"id":N}`, GET an ascending-ID array and DELETE 200 with a bare array of successfully deleted IDs, e.g. `[1,2]` (`[]` if none exist); invalid requests use 400 and unexpected failures use a safe 500 envelope.
- [x] Pin CSV behavior: empty/invalid/negative/overflow IDs rejected; duplicate/missing IDs ignored; input order preserved; 199-character valid input accepted and 200 rejected. Do not reuse the old `> 200` check.
- [x] Test with real PostgreSQL that schema constraints, additional entries of either type, persistence, deletion and repeated/concurrent seeding work. Seed at least one STAGING and one PERMANENT definition without overwriting custom records.
- [x] Test that configured default locations are distinct and reserved for their respective types even after seed deletion. Opposite-type POST returns 400; existing conflicts caused by stored data or changed settings fail startup clearly without overwriting records. This resolves the seed/fallback/global-uniqueness conflict.
- [x] Observe failing tests, then implement the module following Song Service conventions, with its own database and local port 8084. Add `integration`/`contracts` profiles using current report/stub conventions.
- [x] Run `mvn -B -f storage-service/pom.xml -Pintegration,contracts clean install` in the Docker Maven Java-17 runner. Expected: all selected tests execute with zero failures/errors/skips and a local Storage stubs artifact is installed.

## Task 2: Circuit-protected Storage catalog client

**Create in Resource Service:** `src/main/java/com/example/resource/integration/StorageCatalogClient.java`, `StorageDefinition.java`, `StorageType.java`, `StorageCatalogException.java`, `StorageCatalogConfiguration.java`, `StorageFallbackProperties.java`.
**Modify:** `resource-service/pom.xml`, `resource-service/src/main/resources/application.properties`, `resource-service/src/main/java/com/example/resource/api/ResourceErrorHandler.java`.
**Tests:** `resource-service/src/test/java/com/example/resource/integration/StorageCatalogClientTest.java`, `StorageCatalogDiscoveryTest.java`, `resource-service/src/test/java/com/example/resource/contract/StorageCatalogContractTest.java`.

**Interfaces:** `StorageDefinition(long id, StorageType storageType, String bucket, String path)` mirrors Storage HTTP DTOs locally; `StorageCatalogClient.list():List<StorageDefinition>` and `select(StorageType):StorageDefinition`; `StorageDefinition.locationFor(String filename):StoredResourceLocation` builds a bounded S3 key. `StorageCatalogException` represents invalid catalog/protocol/configuration, not successful fallback.

- [x] Test lowest-ID selection from unsorted multi-entry responses; empty/missing-type/null/unknown-type/malformed responses raise a typed error. Non-transient 4xx does not fall back. Map this error to Resource 500 `Storage configuration is invalid`, preserving the error envelope and avoiding the existing Song-specific error message.
- [x] Test connection/read failures, missing Eureka instances, 5xx/408/429 and OPEN rejections return the same configured four-field fallback array. Prove cold-start fallback without ever obtaining a real catalog and prove that successful real responses are used again after recovery.
- [x] Test CLOSED -> OPEN after minimum failed calls, zero physical HTTP calls while OPEN, bounded HALF_OPEN probes, failed probes reopening, and successful probes closing the breaker. Use an HTTP fake/call counts and short test-only durations or explicit test state transitions; avoid fixed sleeps.
- [x] Implement breaker `storageCatalog` with the design's defaults and typed outage classifier. Decorate the actual call before handling fallback so failures reach the breaker; protocol/configuration errors are ignored in breaker statistics and propagated.
- [x] Reuse/clone the existing load-balanced RestClient builder before applying the Storage client's 1000/2000-ms timeouts; do not mutate Song's builder or add an ambiguously injected second builder. No Song retry template or implicit LoadBalancer retry around Storage calls. Constructors must not contact Storage.
- [x] Add a real-provider Storage HTTP consumer contract test using Task 1 stubs. Run Resource fast tests plus `-Pcontracts verify` after installing Storage/Song/Resource provider stubs in dependency order. Expected: client behavior and existing Song integration tests remain green.

## Task 3: Explicit S3 locations, schema migration and restartable promotion

**Modify:** `resource-service/src/main/java/com/example/resource/storage/{ResourceStorage,S3ResourceStorage,ResourceStorageConfiguration}.java`, `resource-service/src/main/java/com/example/resource/persistence/{ResourceEntity,ResourceRepository}.java`, `resource-service/src/main/java/com/example/resource/service/ResourceService.java`, `init-scripts/resource-db/init.sql`.
**Create:** `resource-service/src/main/java/com/example/resource/persistence/ResourceState.java`, `resource-service/src/main/java/com/example/resource/service/ResourcePromotionService.java`, `init-scripts/resource-db/migrations/006-resource-processing-state.sql`.
**Tests:** extend existing `ResourceServiceTest`, `S3ResourceStorageTest`, `S3ResourceStorageIT`, `ResourceDeletionTransactionTest`; create `resource-service/src/test/java/com/example/resource/service/ResourcePromotionServiceTest.java`, `ResourcePromotionIT.java`.

**Interfaces:** replace bucket-bound `put(key,data)` with `ResourceStorage.put(StoredResourceLocation location,byte[] data):void`; add `copy(StoredResourceLocation source,StoredResourceLocation target):void`; keep get/delete explicit-location methods. `ResourcePromotionService.promote(long resourceId):void` owns the durable pin/copy/commit/cleanup sequence in the design. Repository exposes read/write locking lookups and ascending-ID locked bulk lookup for DELETE.

- [x] First test upload resolving STAGING, uploading to its exact prefix and committing state/location before the unchanged event is sent. Existing get/delete must read actual saved locations without a catalog dependency.
- [x] Test successful promotion (copy first, PERMANENT commit second, source cleanup last), byte/content-type preservation, duplicate/missing-resource notifications and resumption using a pinned target despite catalog changes.
- [x] Test a live PERMANENT definition resolving to the current fallback STAGING object, plus invalid pre-existing pinned/cleanup locations equal to the current object. Assert a typed configuration failure, unchanged readable source, and no copy or deletion; never silently mark the resource PERMANENT or clean up its authoritative location.
- [x] Inject failures after target pin, during copy, at promotion DB commit and at source deletion. Assert persisted current/recovery locations, source readability, successful subsequent retry and no fresh copy on a PERMANENT cleanup retry. Repeat boundary cases using real PostgreSQL/LocalStack and a newly constructed service/transaction context rather than relying on in-memory state.
- [x] Test overlapping promotions, GET/promotion and DELETE/promotion with coordinated latches and separate database transactions. Assert distributed row-lock serialization, no stale source read, no recreated object after committed deletion and no duplicate active location. Keep existing Song deletion semantics.
- [x] Implement explicit-bucket S3 operations and the three transaction phases with target/cleanup fields and same-location guards, preserving original UUID filenames. Use normal transactions for pessimistic GET read locks, replacing the existing `readOnly=true`; prove this against PostgreSQL in the GET/promotion test. Update every constructor/caller/test of the old bucket-bound storage implementation; do not leave a silently selected global bucket.
- [x] Add fresh schema and an idempotent legacy migration: existing rows become PERMANENT while bucket/key values stay unchanged. Test repeated migration and enforce state/pair constraints. Do not execute the migration on the user's running database as part of implementation without an applicable instruction.
- [x] Run Resource fast and `-Pintegration verify` suites with Docker Java 17. Expected: real copy/retry/concurrency/migration checks execute and legacy binary/location tests pass without skips.

## Task 4: Confirmed completion messages and promotion consumer

**Create:** `resource-processor/src/main/java/com/example/processor/messaging/ResourceProcessedPublisher.java`, `ResourceProcessedPublicationException.java`; `resource-service/src/main/java/com/example/resource/messaging/ResourceProcessedListener.java`.
**Modify:** `ResourceProcessingService.java`, Processor `ProcessorClientConfiguration.java`, both `ResourceMessagingConfiguration.java` files, both application property files, both Maven test-profile/contract configurations as necessary.
**Tests/contracts:** Processor `ResourceProcessedPublisherTest.java`, existing `ResourceProcessingServiceTest`, `ResourceUploadedListenerTest`, `ResourceMessagingIntegrationTest`, component fixtures and `ProcessorSteps`; new `resource-processor/src/test/resources/contracts/messaging/resource_processed.groovy` and corresponding provider base `contract/ProcessedMessagingContractBase.java`; Resource `ResourceProcessedListenerTest.java`, `ResourcePromotionMessagingIT.java`, `contract/ResourceProcessedContractTest.java`.

**Interfaces:** `ResourceProcessedPublisher.publish(long resourceId):void` sends persistent confirmed mandatory JSON `{"resourceId":N}` to `resources` / `resource.processed`. Consumer queue `resource.promotion`, DLQ `resource.promotion.dlq` bound to `resources.dead` / `resource.promotion.failed`. `ResourceProcessedListener.receive(Message):void` validates the same strict positive-integral-ID schema as the existing uploaded listener, calls `promote`, and returns only after success.

- [x] Test that completion follows successful Song saving (including identical 409 comparison), never follows failed/conflicting processing or deleted-resource skips, and publication failure propagates to prevent acknowledgement.
- [x] Test persistent delivery, content type, mandatory returns, NACK/confirm timeout and bounded retry. Extend the incoming upload listener's transient classifier to recognize completion publication failures; preserve current HTTP classifications.
- [x] Test actual RabbitMQ delivery/redelivery into promotion, malformed/poison message rejection, transient promotion retry and exhausted-message DLQ routing. A duplicate event must not move an already completed resource twice or recreate a deleted one.
- [x] Implement publisher and listener with the design's explicit retry budgets; declare matching durable topology on both sides. Configure a dedicated Resource promotion listener factory so existing Processor listener behavior is retained.
- [x] Add generated completion-message provider and consumer contract verification without replacing the existing upload contract. Ensure Maven profiles select the new integration tests without duplicating them in the fast suite.
- [x] Run provider/consumer contracts, affected integration tests and Processor Cucumber component scenarios with Docker Java 17. Expected: confirmed completion and failure/DLQ behavior are exercised against real RabbitMQ, and legacy upload notification contracts remain green.

## Task 5: Compose, Gateway, bucket provisioning and existing runner compatibility

**Modify:** `compose.yaml`, `compose.testing.yaml`, `compose.development.yaml`, `compose.local-testing.yaml`, `.env.example`, `api-gateway/src/main/resources/application.properties`, `api-gateway/src/test/java/com/example/gateway/GatewayRoutingTest.java`, `api-gateway/src/test/java/com/example/gateway/GatewayErrorHandlingTest.java`, `init-scripts/localstack/create-resource-bucket.sh`, `scripts/test.ps1`, `scripts/verify-module-5.ps1`, `README.md`, `api-gateway/README.md`.

**Interfaces:** Storage app port 8084; storage DB port 5432 internally and 5434 for local development; `/storages` uses `lb://storage-service`. Shared default/fallback location environment variables come from the design. Existing `test.ps1 -Suite All|Unit|Integration|Contracts|Component|E2E` selections remain usable.

- [x] Add Storage DB/service, health checks, Eureka and environment configuration to normal/disposable setups. Keep normal business backend ports internal and add Storage's explicit development debug mapping. Add random storage DB publication to the local-testing override.
- [x] Provision and health-check both default buckets, retaining legacy bucket support. Pass the same default location settings to seeding and fallback. Do not make Resource Service startup/health depend on live Storage availability; do not mark Resource unhealthy when its breaker opens.
- [x] Add the `/storages` route and preserve all existing routes in test property overrides: indexed Spring route lists replace rather than merge. Test forwarding of Storage methods/query/body and preserve existing Gateway error tests.
- [x] Install Storage provider contracts before Resource consumer verification; add Storage to the relevant Unit/Integration/Contracts/All lists. Ensure the new Resource messaging IT is selected only in integration.
- [x] Update Module 5 local verification to build/start Storage plus its isolated DB and location variables, propagate existing JavaOptions and preserve its owned-process cleanup. Keep Module 5 two-Song-instance/routing checks functional.
- [x] Run Compose configuration checks for normal, development and disposable combinations, Gateway tests and Module 5 Docker/local checks. Expected: new route/discovery works and prior Module 5 scenarios remain valid.

## Task 6: Full lifecycle and live circuit-breaker verification

**Create:** `scripts/verify-module-6.ps1`, `compose.fault-tolerance-testing.yaml`, `docs/module-6-verification.md`.
**Modify:** `e2e-tests/src/test/java/com/example/e2e/ApiSteps.java`, `e2e-tests/src/test/resources/features/e2e/resource-lifecycle.feature`, `docs/testing-strategy.md`; add `e2e-tests/src/test/resources/features/e2e/storage-lifecycle.feature` and dedicated step class `StorageSteps.java` as appropriate.

**Interfaces:** `verify-module-6.ps1 -Mode Docker|Local` owns a unique test project, polls with deadlines and exits nonzero on failed assertions. Use service logs/internal test-database and S3 inspection to verify states; do not introduce a public status/test endpoint. Fault-testing configuration may shorten breaker durations but must retain the real HTTP/AMQP/S3/SQL path.

- [x] Verify seeded definitions via Gateway CRUD, extra entries, lowest-ID selection and the strict CSV boundary. When selection tests use custom buckets, provision those only in the owned LocalStack instance and clean them with that project.
- [x] Pause the owned Processor listener/container, upload through Gateway, and assert STAGING plus real bucket/key/bytes before processing. Resume it; await metadata and PERMANENT state, verify destination bytes/content type, source removal and download/delete behavior. Queue pause requires no production test endpoint.
- [x] Redeliver an actual completion event to prove idempotence. Add recovery cases for pending cleanup and failures without treating Song metadata appearing as proof that promotion finished.
- [x] Stop only the owned Storage Service. Upload enough files to observe breaker OPEN and prove the entire recording lifecycle continues on fallback. Then restart Resource with Storage still absent to prove cold-start independence. Keep LocalStack/RabbitMQ/databases healthy, and distinguish Storage server failure from S3 outage.
- [x] Restart Storage, generate bounded calls until HALF_OPEN then CLOSED are observed, and verify real catalog selection resumes. Confirm no physical HTTP attempts are made while OPEN using test-controlled HTTP counts in Task 2 and state-transition logs in the live run.
- [x] Run existing E2E scenarios and both Module 6 environments; then `./scripts/test.ps1 -Suite All` once on the final implementation. Record current XML counts, failures, skips, lifecycle assertions, runtime versions and actual commands. Prior 153-test evidence must not be presented as current Module 6 proof.
- [x] Always stop only owned Java PIDs and disposable test projects in nested cleanup blocks, including failure paths; preserve developer services/data. Review the final diff against every assignment item and run `git diff --check`.

## Implementation handoff

The user approved this plan for implementation on 2026-11-18. Multiple entries per type are supported with lowest-ID selection. Storage DELETE returns the assignment's bare array. The implementation includes same-location protection, reserved seed locations, persisted promotion recovery, PostgreSQL locking and strict API/catalog validation. One independent final review was completed; its canonical-location finding and two coverage gaps were fixed and verified. Current test evidence and command outcomes are recorded in output/module-6/test-report.md. Commit and PR delivery were authorized on 2026-11-19 after verification.

Execution result: 252 current automated cases passed with zero failures/errors/skips. All module-level tests passed; E2E was rerun successfully after a Docker registry DNS failure interrupted its initial setup. Module 6 Docker/Local and Module 5 Docker/Local live checks all passed. See output/module-6/test-report.md for counts, exact command outcomes and operating boundaries.
