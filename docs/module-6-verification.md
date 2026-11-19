# Module 6: Storage and fault tolerance verification

Run from the repository root in PowerShell 7 with Docker Desktop running.

## Automated suites

    ./scripts/test.ps1 -Suite All

Selections: Unit, Integration, Contracts, Component, E2E.
The runner uses Docker Maven with Java 17 and a disposable Compose project for E2E. Generate both message providers before consumers. XML evidence is under each module's target/*-reports.

## Live outage checks

    ./scripts/verify-module-6.ps1 -Mode Docker
    ./scripts/verify-module-6.ps1 -Mode Local

Local mode starts Java applications on the host and isolated infrastructure in Docker. It requires Java on PATH. On Windows JVMs affected by Unix-domain loopback initialization, pass a **nonexistent** path so the JDK uses its TCP fallback:

    ./scripts/verify-module-6.ps1 -Mode Local -JavaOptions "-Djdk.net.unixdomain.tmpdir=C:/module6-nonexistent-socket-directory"

Do not create that directory. Docker Java 17 tests and host-JVM verification are separate evidence.

The runner checks:

1. Storage defaults, additional entries, lowest-ID selection, and 199/200-character CSV boundaries.
2. With Processor stopped, upload persists STAGING and original bytes at the catalog location.
3. After Processor resumes, confirmed completion leads to PERMANENT, unchanged bytes/content type, and source removal.
4. A duplicate completion is acknowledged without losing the recording; download/deletion remain functional.
5. With Storage stopped, upload, metadata processing, promotion, download and deletion continue through fallback.
6. Resource restarts while Storage remains absent and completes another recording.
7. Restarting Storage yields HALF_OPEN then CLOSED; a changed live catalog location proves fallback has ended.

Evidence goes to output/module-6/<generated-project>/results.json and application logs. The runner reads its owned databases/S3 instance, adds no public diagnostic endpoint, stops only owned Java processes, and removes only its generated Compose project.

Failure injection is also automated in Resource integration tests: failed copy, DB rollback after copy, failed source cleanup followed by a fresh service context, GET/DELETE races, migration replay, malformed messages and exhausted transient retries reaching the DLQ.

## Storage API

- POST /storages: 200 with {"id":N}.
- GET /storages: array of id/storageType/bucket/path, ordered by ID.
- DELETE /storages?id=1,2: 200 bare array of deleted IDs; duplicates/missing IDs ignored; CSV shorter than 200 characters.
- Only STAGING/PERMANENT; multiple entries per type allowed. Resource chooses the lowest ID.
- Paths are canonical absolute prefixes such as /files. Default bucket/prefix pairs are reserved for their own type. Definition deletion never removes S3 objects or resources.
- Custom buckets are provisioned separately. LocalStack creates both default buckets and retains the legacy bucket.

## Existing Resource database

Fresh databases use init-scripts/resource-db/init.sql. Before starting this version against an existing database, apply init-scripts/resource-db/migrations/006-resource-processing-state.sql using the database owner's normal migration procedure. It is repeatable, retains existing bucket/key values, and marks legacy rows PERMANENT. No user database is migrated/reset automatically.

## Recovery model

Resource pins the destination before copying, commits PERMANENT with pending source-cleanup information, then deletes the source. Redelivery resumes after a crash. GET holds a database read lock during S3 reading; promotion/DELETE use write locks. Identical source/destination or cleanup/current locations are rejected to protect the authoritative object.

Processor publishes {"resourceId":N} to resources/resource.processed after successful metadata saving, with mandatory routing and correlated confirms. Resource consumes resource.promotion; permanent failures/exhausted transient retries go to resource.promotion.dlq. Correct the cause before explicitly replaying a DLQ message. No automatic replay or general outbox framework is added; the pre-existing upload commit-to-publish crash window remains.

The Storage breaker uses window 5, minimum 3 calls, 50% failure threshold, ten-second OPEN wait and two HALF_OPEN probes. The disposable fault-testing override shortens the wait to two seconds. Invalid catalog data is a configuration failure, not fallback. Broker reconnection belongs to the listener container; promotion retries cover transient S3/network and database failures.
