# Module 6 fault tolerance design

Status: implemented after the user approved implementation on 2026-11-18. See docs/module-6-verification.md for verification and operating instructions.

Assignment: `C:/Users/SerhiiSenkiv/Downloads/README (8).md`.
Inspected baseline: `6e69420` on `feature/module-5-service-discovery`.

## Goal and boundaries

Add Storage Service, store newly uploaded files in STAGING, promote successfully processed files to PERMANENT after an asynchronous notification, and tolerate Storage Service outages using Resilience4j and local stub definitions.

- Keep Java 17, Spring Boot 3.5.16, Spring Cloud 2025.0.3, PostgreSQL, RabbitMQ and LocalStack. Select only a Java-17-compatible Resilience4j version; no unrelated dependency upgrades.
- Preserve existing Resource/Song public endpoints, response envelopes, binary behavior and `resource.uploaded` payload.
- Storage Service stores general storage definitions. Resource Service stores each resource's state and physical locations; no cross-service database foreign keys.
- Create only the three specified Storage endpoints. Do not invent PUT/PATCH or GET-by-ID APIs.
- Preserve unrelated dirty work. Implementation is isolated on feature/module-6-fault-tolerance. Commit and PR delivery were authorized on 2026-11-19.

## Decisions and alternatives

**User-confirmed:** allow multiple storage entries for each of the two allowed types; Resource Service selects the lowest-ID entry of the required type.

**Recommended approach:** extend the current upload/event processing pipeline. Add one completion event, an explicit Resource promotion operation with persisted recovery locations, and a dedicated circuit breaker for Storage catalog lookups. Existing publisher confirms, retries, contracts and test infrastructure remain useful.

**Alternatives considered:** a best-effort copy/update/delete sequence is smaller but cannot reliably finish cleanup after crashes; a general workflow coordinator or outbox framework substantially expands the assignment. Use local recovery fields and incoming-message redelivery for this new operation. Do not introduce a general Saga/outbox framework or claim distributed exactly-once guarantees. The existing upload database-commit-to-publish crash window is unchanged by this scope.

The remaining choices below describe the implementation of the approved plan; they are not additional assignment requirements.

## Storage API and selection

Storage Service: application name `storage-service`, local port 8084, separate `storage-db` (local development DB port 5434). Register with Eureka and route `/storages` and `/storages/**` through the Gateway using `lb://storage-service`.

Storage representation: `{id: positive long, storageType: "STAGING" | "PERMANENT", bucket: string, path: string}`.

| Endpoint | Contract |
| --- | --- |
| `POST /storages` | Input has `storageType`, `bucket`, `path`; return 200 with `{"id":N}`. Invalid fields or duplicate bucket/path return 400 using the existing error envelope. |
| `GET /storages` | Return 200 with a JSON array, ordered by ID ascending. |
| `DELETE /storages?id=1,2` | Deduplicate positive integral IDs, ignore missing IDs, preserve input order in the result; return 200 with a JSON array of successfully deleted IDs, for example `[1,2]`. Require a nonempty CSV whose character length is strictly less than 200. |

Validate required fields and the two enum values. Proposed bounds: bucket follows S3 general-purpose naming rules; path is a root or slash-separated prefix with a leading slash, no empty interior segments, backslashes, `.` or `..` segments, and at most 900 UTF-8 bytes. Normalize trailing slashes (`/files/` becomes `/files`, `/` stays `/`); enforce uniqueness on `(bucket, normalized path)` across all entries. Object keys omit the leading slash and must fit S3's 1024-byte key limit.

Seed defaults: STAGING -> `resources-staging`, `/files`; PERMANENT -> `resources-permanent`, `/files`. Ensure at least one entry of each type on application startup without overwriting existing entries, duplicating seeds, or deleting custom entries. Use database uniqueness and conflict-safe inserts for repeated/concurrent startup. Seed values and Resource fallback values use the same environment variables: `STAGING_STORAGE_BUCKET`, `STAGING_STORAGE_PATH`, `PERMANENT_STORAGE_BUCKET`, `PERMANENT_STORAGE_PATH`.

The two configured default locations must be distinct. Reserve each configured `(bucket, normalized path)` for its designated type, even if its seed record is deleted: POST with the opposite type returns 400. If existing database contents or changed environment settings violate that reservation, fail Storage startup with a clear configuration error; do not overwrite the conflicting entry or silently choose a different seed. This makes the bootstrap and fallback rules compatible with additional entries.

Deleting a Storage definition changes future selection only. It never deletes buckets, S3 objects or Resource records. Catalog creation does not provision arbitrary buckets; the operator must create buckets for custom definitions. LocalStack automatically provisions the two default buckets. Retain the legacy `resources` bucket for existing records while migrating.

## Storage catalog client and circuit breaker

Resource Service fetches `GET /storages` through Eureka, validates the response and chooses the lowest positive ID for the requested type. Missing required types, malformed successful responses and unexpected non-transient HTTP 4xx responses are configuration/protocol errors; do not hide them with fallback or treat them as dependency outages.

Use an explicit Resilience4j breaker named `storageCatalog`, applied to the physical lookup/validation operation before fallback. Proposed defaults: count-based window 5, minimum calls 3, failure threshold 50%, open duration 10 seconds, half-open permitted calls 2. Storage HTTP connect/read timeouts: 1000/2000 ms. One physical HTTP attempt per allowed breaker call; do not wrap this client in the existing Song retry template or enable implicit LoadBalancer retries.

Count network/connect/read-timeout/no-Eureka-instance failures and HTTP 5xx/408/429 as outages. Supply the configured immutable fallback array for those failures and rejected OPEN/HALF_OPEN calls. Ignore protocol/configuration exceptions in breaker statistics and propagate them as a typed Storage catalog error, mapped to a safe Resource API 500 error rather than the existing Song-specific failure message.

Fallback uses the same four-field representation and two supported type values as real Storage responses, with positive stable stub IDs. The actual bucket/path snapshots, not catalog IDs, are persisted with resources. Static fallback deliberately uses configured defaults rather than a last-good cache of administrative edits.

No startup HTTP lookup, dependency gate on Storage Service health, or Resource liveness failure when the breaker opens. Log breaker transitions/fallback usage for verification without adding public diagnostic business endpoints. Database, broker and S3 failures remain real failures; this breaker protects Storage catalog availability only.

## Resource records, upload and promotion

Add `state` (`STAGING`/`PERMANENT`) and nullable paired fields `promotion_target_bucket/key`, `cleanup_source_bucket/key`. Enforce valid states and both-or-neither nullability for each location pair.

New uploads resolve STAGING before S3 I/O, build a UUID-based filename under the returned prefix, upload to that explicit bucket/key, and persist STAGING plus the actual location before publishing the unchanged upload event. Keep current database-failure cleanup and publisher-failure behavior. GET/DELETE use stored physical locations; they do not require a catalog lookup.

On completion notification, `promote(resourceId)` performs these restartable steps:

1. If the row is missing, return successfully. If already PERMANENT, skip catalog selection/copy and finish any recorded source cleanup.
2. For STAGING without a pinned target, fetch PERMANENT outside the row lock. In a short transaction lock/recheck the row and persist the target bucket/key if still needed. Keep the original UUID filename. A concurrent worker uses the already pinned target; later catalog edits or fallback do not change it. Before pinning and again before copying, reject a target equal to the current physical bucket/key with a typed configuration error. Leave the STAGING object readable, perform no copy/delete and require correction plus message replay after exhausted retries; never record the authoritative object as its own cleanup source. Apply the same guard to pre-existing pinned/cleanup locations so invalid legacy data cannot delete the current object.
3. In another transaction acquire the same row's write lock, recheck state, copy the current object to the pinned target, then atomically update current bucket/key and state to PERMANENT, record the old location as pending cleanup, and clear target fields. If copying or commit fails, the current STAGING location remains authoritative and the pinned target permits safe retry. Never mark PERMANENT before a successful copy.
4. After that commit, lock/recheck the row, delete the recorded old object and clear cleanup fields in a transaction. Failure preserves cleanup information; a repeated notification retries cleanup without copying again. Ack the completion message only after all required work succeeds.

Use PostgreSQL row locks across multiple Resource instances, not an in-memory lock. Downloads acquire a compatible read lock while reading the selected S3 object. Change the existing GET transaction from `readOnly=true` to a normal transaction because PostgreSQL locking reads are not permitted in a read-only transaction; GET still performs no data mutation. DELETE obtains write locks in ascending ID order and removes all distinct recorded current/pending locations before deleting the row. This serializes promotion with download/delete so a late completion cannot resurrect a deleted resource. Preserve the existing Song deletion contract; do not claim general atomicity of its existing S3/SQL/Song deletion sequence.

Keep S3 operation timeouts bounded as they are today. Use `copyObject` with the AWS SDK and retain the MP3 content type/bytes. Copy and deletion are separate operations; delete the source only after the database points at the completed copy.

## Completion messaging

- Exchange: existing durable direct exchange `resources`.
- New routing key: `resource.processed`; payload: `{"resourceId": positive long}`; persistent JSON with message ID, mandatory routing and publisher confirms.
- Resource consumer queue: durable `resource.promotion`.
- Dead-letter routing: existing `resources.dead`, key `resource.promotion.failed`, durable queue `resource.promotion.dlq`.
- Processor publishes only after `SongClient.save` succeeds, including its identical-409 duplicate branch. Deleted-resource skips and failed/conflicting processing must not publish completion.
- Publication failure is transient for the existing upload listener's retry classifier. Redelivery repeats idempotent metadata saving and completion publishing. Publish retry budget is 3; incoming upload retry remains 5, giving at most 15 completion sends per exhausted delivery cycle. No incoming acknowledgement before confirmed completion publication.
- Resource promotion retries transient S3/network and database locking failures (broker reconnection belongs to the listener container) up to 5 attempts with existing bounded exponential-backoff conventions. Malformed input and permanent configuration/storage failures are dead-lettered rather than requeued forever. Exhaustion retains the event in the DLQ for explicit operator replay; no automatic DLQ replay is introduced.

## Database evolution and compatibility

Fresh schemas gain the new columns. Provide a separate idempotent SQL migration for an existing Resource database: classify existing rows as PERMANENT, preserve all bucket/key values, and leave recovery fields null. Remove any temporary backfill default so future uploads set their state explicitly. Document application of the migration; do not automatically clear databases, volumes or buckets. Existing legacy file downloads/deletions keep using their recorded locations.

No new public Resource status endpoint is required. Verification inspects owned test databases and S3 directly. Existing public HTTP/message contracts stay compatible; add Storage HTTP and processed-message contracts.

## Verification and completion criteria

- Storage validation, normalization, selection, seed idempotence and strict CSV boundaries (199 accepted, 200 rejected).
- Real PostgreSQL Storage persistence/seeding/migration and real S3 copy/delete with exact byte comparison.
- Cold-start fallback, failed calls opening the breaker, no backend calls while OPEN, half-open success/failure and normal recovery; protocol errors do not silently use stubs.
- Completion publication ordering, publisher confirms/unrouted/NACK behavior, redelivery and terminal DLQ behavior with real RabbitMQ.
- Promotion retries after copy/DB/cleanup failures, duplicate/concurrent messages, GET/DELETE races, and preserved legacy locations. Database restart/crash boundaries retain enough state to finish without source data loss.
- Docker and local execution with Storage registered/routed; stop Storage and continue upload/process/promote/download/delete using the default buckets; restart it and observe breaker recovery. Include Resource startup while Storage is absent.
- Extend the current test categories and run `scripts/test.ps1 -Suite All`; the historical 153 cases are orientation only, not proof that Module 6 passes. Record current counts and skips separately from screenshots/PDF output.

References: [Resilience4j breaker behavior](https://resilience4j.readme.io/docs/circuitbreaker), [AWS S3 client operations](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html). Keep the existing SDK version unless a required operation is actually unavailable.
