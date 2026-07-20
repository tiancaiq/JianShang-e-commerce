# AI-RAG-02A Product Listing Source Publication

Status: implemented and verified on 2026-07-18.

Release: V3.

## Requirement

Implement the Product Service-owned portion of `AI-RAG-02` without adding
agent ingestion, embeddings, retrieval, customer routes, or frontend work.

The authoritative source body contains only:

- listing title;
- approved public description;
- public city and region;
- decimal price amount and currency.

## Implemented Contract

- Forward-only Flyway migration creates immutable
  `listing_knowledge_versions` and durable `outbox_events`.
- Active approved individual-listing transitions create `ACTIVE` source
  versions. Leaving eligibility creates an `INVALIDATED` tombstone.
- The source version equals the authoritative listing aggregate version.
- Snapshot and outbox writes participate in the existing listing transaction.
- A bounded backfill worker creates initial active snapshots for eligible
  listings that predate the migration.
- Exact source reads and watermark-stable cursor export require the distinct
  `X-Agent-Internal-Service-Token`.
- Events use the listing ID as Kafka key and contain source references only.
- The Kafka publisher is disabled by default, claims bounded batches, waits
  for broker acknowledgement, and retries failures with bounded exponential
  backoff and jitter.
- Outbox count, age, publish result, failure class, and lost-claim metrics are
  exposed without high-cardinality labels.

## Routes

```text
GET /api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}
GET /api/v1/internal/agent/knowledge/listings/export?cursor=&limit=
```

The exact route never falls back to the latest version. Export returns only
the latest active source per listing as of its fixed watermark.

## Privacy Boundary

Snapshots, events, responses, logs, and metrics exclude seller identity,
contact data, exact address or coordinates, media, moderation evidence,
payment/delivery preferences, storage fields, and credentials.

## Migration

```text
product-service/src/main/resources/db/migration/catalog/
V202607180000__create_listing_knowledge_publication.sql
```

Existing migrations were not rewritten.

## Tests Added

- immutable activation, update, and tombstone persistence;
- snapshot/outbox rollback atomicity;
- reference-only payload and forbidden-field checks;
- actual moderation-approval transition publication;
- exact-version authorization, cross-purpose token denial, and no fallback;
- stable export cursor and tombstone exclusion;
- exclusive outbox claims and retry release;
- acknowledged and failed Kafka publisher behavior;
- migration table presence.

No live provider or OpenAI request is part of this slice.

## Verification Status

Static diff/whitespace checks passed. The complete Product Service reactor
suite passed with:

```text
mvn -Dnet.bytebuddy.experimental=true -pl product-service -am test
```

Results:

- Product Service: 133 tests passed, 0 failures, 0 errors, 0 skipped;
- shared reactor modules: 29 tests passed, 0 failures, 0 errors, 0 skipped;
- Flyway applied the catalog migrations, including
  `V202607180000__create_listing_knowledge_publication.sql`, to the MySQL 8.4
  Testcontainers database;
- the listing lifecycle, exact/export authorization, transactional outbox,
  exclusive claims, retry release, and Kafka acknowledgement paths passed.

The `net.bytebuddy.experimental` flag is required only because this verification
host runs Java 25 while the current test dependency officially supports Java
24. The repository baseline remains Java 21.

`AI-RAG-02B` was implemented and verified on 2026-07-19 using the approved
Python/FastAPI agent runtime.

## Deferred

- Agent MySQL, Kafka consumption, and source client: completed in
  `AI-RAG-02B`.
- Sanitization, chunking, embeddings, and OpenSearch ingestion:
  `AI-RAG-02C`.
- Rebuild/promotion and agent-side physical deletion: `AI-RAG-02D`.
- Non-listing authoritative sources: `AI-RAG-02E`.
