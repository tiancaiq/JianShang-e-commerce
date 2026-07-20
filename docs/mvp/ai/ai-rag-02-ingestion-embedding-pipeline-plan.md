# AI-RAG-02 Ingestion And Embedding Pipeline Plan

Status: implementation plan complete. `AI-RAG-02A` was implemented and
verified on 2026-07-18. `AI-RAG-02B` was implemented and verified on
2026-07-19. `AI-RAG-02C` was implemented and verified on 2026-07-19;
the full local backlog was activated successfully. `AI-RAG-02D` is
implemented and locally deployed; no paid rebuild or promotion was run.

Release: V3.

## Goal

Build the durable pipeline that turns approved, versioned public source
content into the agent-owned OpenSearch knowledge projection created by
`AI-RAG-01`.

The first executable path is `LISTING` content because Product Service and the
listing lifecycle already exist. Its initial semantic inputs are exactly:

- listing name/title;
- approved public description;
- public city and region;
- listed price and currency.

Price, location, eligibility, and listing version remain current authoritative
facts from Product Service. Their presence in a versioned vector passage helps
semantic discovery but never lets vector content override `getListing`.

This planning slice adds no dependency, migration, runtime configuration,
provider request, or product endpoint.

## Requirement And Roadmap Mapping

- `AI-RAG-00`: source ownership, precedence, versioning, visibility,
  invalidation, privacy, and recovery contract.
- `AI-RAG-01`: strict chunk document, versioned indexes, read/write aliases,
  safe write primitives, readiness, and metrics.
- `AI-RAG-02`: source publication, durable ingestion, sanitization, chunking,
  embeddings, indexing, deletion, and rebuild.
- `AI-05`: future listing-bound customer-service assistant.
- Precedes `AI-RAG-03` filtered retrieval.

## Gaps At Planning Time

- Product Service owns all four initial listing fields and listing version, but
  the current public DTO omits version.
- Product Service has no immutable AI-source snapshot history.
- Product Service has no transactional listing outbox or Kafka publisher.
- Internal Product Service routes are permitted by Spring Security and rely on
  route-specific token checks; the AI path needs its own token and must not
  reuse the commerce credential.
- The agent service has no MySQL integration, Kafka consumer, source API
  client, durable ingestion queue, embedding adapter, or rebuild state.
- `AI-RAG-01` writes only through the agent knowledge write alias and does not
  yet mirror live changes while read/write aliases differ during a rebuild.
- No authoritative policy, safety, FAQ, or category-guidance version store
  exists yet.

These gaps make a single implementation change too broad. `AI-RAG-02` must be
delivered through the ordered sub-slices below.

## Approved Decisions

1. Product Service publishes immutable, versioned public listing-knowledge
   snapshots. The agent never reads Product Service tables.
2. Kafka events contain source identity and version references, not listing
   text, embeddings, exact locations, or private data.
3. Product Service stores the snapshot and outbox event in the same
   transaction as the listing state/version change.
4. The agent first durably records a validated event in MySQL, then commits
   the Kafka offset. External source, embedding, and OpenSearch calls run from
   a retryable database-backed worker.
5. Initial listing language is `und` because the current listing contract has
   no authoritative language field. Do not infer language with an LLM.
6. The initial embedding baseline is OpenAI `text-embedding-3-small` with
   `1536` dimensions and float output. Deployment must set the provider,
   model, and dimension explicitly so they match the `AI-RAG-01` mapping.
7. The embedding provider remains replaceable. Adding Gemini or another
   provider requires an approved architecture/provider decision and a new
   index generation when embedding identity changes.
8. OpenAI embedding requests use bounded online Embeddings API calls, not the
   24-hour Batch API, because source changes have a 15-minute propagation
   target.
9. Deterministic source normalization, chunking, and document IDs make retries
   safe across MySQL, provider, and OpenSearch transaction boundaries.
10. Live updates are mirrored to the validated read and write generations
    while aliases differ, so a long rebuild does not freeze the currently
    served generation.
11. Promotion remains an explicit operator action and requires a successful
    rebuild record plus event-lag and failure gates.
12. No customer-facing agent, retriever, gateway, frontend, or conversation
    persistence is added in this roadmap item.

OpenAI's current documentation identifies `text-embedding-3-small` as the
default small embedding model, with a default vector length of `1536`. The
Embeddings API supports multiple strings per request and an explicit
`dimensions` parameter for third-generation embedding models:

- https://developers.openai.com/api/docs/models/text-embedding-3-small
- https://developers.openai.com/api/docs/guides/embeddings
- https://developers.openai.com/api/reference/resources/embeddings/methods/create

## Ordered Implementation Sub-Slices

### AI-RAG-02A Product listing source publication

Implementation reference:

- `docs/mvp/ai/ai-rag-02a-product-listing-source-publication.md`

Owns only Product Service changes:

- immutable listing-knowledge snapshot table;
- Product Service outbox table and publisher;
- `listing.activated`, `listing.updated`, and `listing.deactivated` version-1
  payloads;
- authenticated exact-version source read;
- authenticated cursor-paginated active-source export;
- transition, authorization, migration, and publisher tests.

No agent-service ingestion or embedding call belongs in `02A`.

### AI-RAG-02B Agent durable ingestion intake

Implementation reference:

- `docs/mvp/ai/ai-rag-02b-agent-durable-ingestion-intake.md`

Owns only agent ingestion infrastructure:

- agent Flyway migration mechanism and initial ingestion tables;
- strict event envelope and payload schemas;
- async Kafka consumer with manual offset commits;
- durable, deduplicated ingestion job enqueue;
- authenticated Product Service source client;
- bounded job claiming, retry scheduling, and dead-letter state;
- Kafka, MySQL, source-client, and shutdown tests.

No embedding or OpenSearch write belongs in `02B`.

### AI-RAG-02C Listing sanitization, embedding, and indexing

Implementation reference:

- `docs/mvp/ai/ai-rag-02c-listing-embedding-indexing.md`

Owns the first content path:

- deterministic listing source normalization;
- listing summary and description chunking;
- replaceable `EmbeddingProvider`;
- OpenAI embedding adapter;
- dimension, ordering, token, timeout, and batch validation;
- idempotent live indexing, superseded-version invalidation, and state update;
- synthetic end-to-end listing ingestion tests.

No production retriever or customer API belongs in `02C`.

### AI-RAG-02D Rebuild, tombstone, and recovery operations

Owns:

- full listing export/rebuild runs and checkpoints;
- live read/write-generation mirroring during rebuild or rollback;
- tombstone application and physical deletion worker;
- promotion gates and explicit operator commands;
- crash/replay, concurrent-update, lag, rollback, and recovery tests.

### AI-RAG-02E Non-listing authoritative sources

Adds adapters only after their owning modules expose immutable versioned
records and durable events:

- `MARKETPLACE_POLICY`;
- `SAFETY_GUIDANCE`;
- `MARKETPLACE_FAQ`;
- `CATEGORY_GUIDANCE`.

The agent service must not create authoritative policy or category records to
unblock itself. Full `AI-RAG-02` completion waits for these owner contracts,
but the listing pipeline can be implemented and verified first.

`AI-KNOW-01` now provides the implemented and locally deployed
`CATEGORY_GUIDANCE` owner contract. Its Agent Service adapter is planned as
the isolated `AI-RAG-02E-CATEGORY` sub-slice:

```text
docs/mvp/ai/ai-rag-02e-category-guidance-adapter-plan.md
```

Policy, safety, and FAQ adapters remain deferred pending their owners.

## End-To-End Listing Flow

```text
listing transaction
    -> immutable Product Service knowledge snapshot
    -> Product Service outbox row
    -> bounded outbox publisher
    -> Kafka listing event keyed by listingId
    -> agent consumer validates and durably enqueues event
    -> Kafka offset commit
    -> ingestion worker claims durable job
    -> exact Product Service source-version read
    -> canonical hash verification
    -> deterministic sanitization and chunking
    -> bounded embedding request
    -> strict OpenSearch upsert
    -> superseded-version invalidation/deletion
    -> agent source state and job success
```

Kafka, provider, or OpenSearch failure cannot roll back or block the listing
transaction. The outbox and ingestion job retry independently.

## Product Service Source Contract

### Immutable snapshot

Proposed `listing_knowledge_versions` fields:

- `listing_id`;
- `source_version`, equal to the authoritative listing row version;
- nullable `supersedes_version`;
- `ACTIVE` or `INVALIDATED` lifecycle;
- `INDIVIDUAL` seller type;
- visibility, initially `PUBLIC`;
- language, initially `und`;
- title;
- description;
- decimal price amount stored without floating-point conversion;
- ISO 4217 currency;
- public city and region;
- nullable effective, invalidated, and published timestamps;
- canonical source-content SHA-256;
- created timestamp.

Primary key: `(listing_id, source_version)`.

Indexes:

- `(lifecycle, listing_id, source_version)` for active export;
- `(listing_id, lifecycle, source_version)` for predecessor lookup;
- `(created_at, listing_id, source_version)` for operational audit.

No seller ID/name, contact field, exact address, coordinates, media URL/bytes,
moderation evidence, internal note, payment preference, delivery preference,
storage field, or credential is stored in the knowledge snapshot.

### Snapshot creation rules

Every listing mutation that increments the authoritative listing version must
evaluate knowledge publication:

- entering active, approved, individual visibility creates `ACTIVE` and
  `listing.activated`;
- remaining active and approved after any version increment creates another
  immutable `ACTIVE` version and `listing.updated`, even when the four content
  fields have the same hash;
- leaving eligibility creates `INVALIDATED` and `listing.deactivated`;
- non-individual and never-eligible listings create no listing knowledge
  source.

This rule prevents an image or lifecycle version change from making otherwise
valid chunks fail the future exact-version retrieval check.

### Exact source read

Proposed internal route:

```text
GET /api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}
```

The route:

- requires `X-Agent-Internal-Service-Token`;
- compares the token in constant time;
- accepts exact IDs and versions only;
- returns one strict active snapshot or tombstone;
- returns `404` for unknown versions and `403` for invalid credentials;
- never falls back to the latest version;
- never returns private or moderation fields.

Active response direction:

```json
{
  "sourceType": "LISTING",
  "sourceId": "01L...",
  "sourceVersion": "12",
  "supersedesVersion": "11",
  "lifecycle": "ACTIVE",
  "visibility": "PUBLIC",
  "language": "und",
  "effectiveFrom": "2026-07-18T12:00:00Z",
  "contentHash": "lowercase-sha256",
  "content": {
    "title": "Used bicycle",
    "description": "Seller-provided public description.",
    "price": {"amount": "250.00", "currency": "USD"},
    "publicLocation": {"city": "Irvine", "region": "CA"}
  }
}
```

An invalidated response omits `content`, includes `invalidatedAt`, and
identifies the exact `supersedesVersion` to invalidate.

### Rebuild export

Proposed internal route:

```text
GET /api/v1/internal/agent/knowledge/listings/export?cursor={opaque}&limit={1..200}
```

It returns strict full active snapshots, an opaque cursor, `hasMore`, and a
safe export watermark. Ordering is stable and cursor-based. It never returns
draft, pending, rejected, paused, sold, closed, removed, business, or private
records.

### Event contract

Use the approved event names and envelope. Event payloads carry references,
not source bodies:

```json
{
  "listingId": "01L...",
  "listingVersion": "12",
  "knowledgeLifecycle": "ACTIVE",
  "supersedesVersion": "11",
  "language": "und"
}
```

Rules:

- producer is `product-service`;
- aggregate type is `listing`;
- aggregate ID and Kafka message key are the listing ID;
- event version is `1`;
- `listing.updated` is emitted only after a new immutable source version is
  stored;
- `listing.deactivated` identifies the last active version;
- payloads contain no listing text or private data;
- unknown additive fields are ignored;
- changing field meaning requires a new event version.

### Outbox

Product Service adds a forward-only `outbox_events` migration. The snapshot,
listing mutation, and outbox insert commit atomically.

The publisher:

- reads a bounded unpublished batch ordered by creation time;
- uses row locking safe for multiple instances;
- sends with the listing ID as partition key;
- marks published only after broker acknowledgement;
- never drops the listing transaction when Kafka is unavailable;
- may publish duplicates after a crash; consumers deduplicate by event ID;
- applies bounded retry/backoff and exposes backlog age/count metrics.

The existing best-effort Product Service search projection remains separate
and is not refactored into this outbox slice.

## Agent MySQL Contract

Migrations live under `agent-service/db/migration/` and are applied by an
explicit Flyway deployment/CI job. FastAPI startup never creates or updates
schema.

### `processed_events`

Stores consumer name, event ID, event type/version, aggregate reference,
payload hash, accepted time, and correlation ID. Unique
`(consumer_name, event_id)` provides durable deduplication.

### `knowledge_ingestion_jobs`

Stores:

- job ID and unique event ID;
- source type, ID, version, language, and lifecycle;
- nullable superseded version;
- event occurrence time and payload hash;
- `PENDING`, `PROCESSING`, `RETRY_WAIT`, `SUCCEEDED`, or `DEAD_LETTER`;
- bounded attempt count and next-attempt time;
- claim owner/expiry;
- safe last error code;
- created, updated, and completed times.

Indexes:

- `(status, next_attempt_at, created_at, id)` for claiming;
- `(source_type, source_id, source_version)` for operations;
- `(claim_expires_at, status)` for abandoned-claim recovery.

The table stores no source body, passage, embedding, provider response, or
credential.

### `knowledge_source_state`

One row per `(source_type, source_id, language)`:

- latest observed source version;
- latest successfully indexed version and source-content hash;
- last superseded version;
- `ACTIVE`, `TOMBSTONED`, or `FAILED`;
- chunker version and embedding identity;
- source event occurrence, indexed, invalidated, and updated times;
- last event ID and safe failure code;
- optimistic version.

Newer source versions always win. Older out-of-order jobs become no-op success.
The same version with a different source hash fails closed as a source
contract violation.

### `knowledge_rebuild_runs`

Added in `02D`:

- run ID, source type, target generation, embedding/chunker identity;
- source export cursor and watermark;
- expected, processed, failed, skipped, and tombstoned counts;
- start, update, completion, and promotion times;
- `RUNNING`, `FAILED`, `READY_TO_PROMOTE`, `PROMOTED`, or `ROLLED_BACK`;
- safe last error code and initiating operator identity.

The migration does not create session, message, invocation, or tool-call
tables reserved for `AI-CS-01A`.

## Kafka Intake And Job Processing

The agent uses an asyncio Kafka adapter with:

- one configured allowlisted topic;
- a stable consumer group and client ID;
- `enable_auto_commit=false`;
- strict envelope and payload validation before persistence;
- a MySQL transaction that inserts deduplication and job records;
- offset commit only after that transaction succeeds;
- clean consumer start/stop in application lifespan;
- rebalance handling that completes or abandons in-flight intake safely.

The database-backed worker, not the Kafka poll loop, performs network calls.
This prevents provider throttling or OpenSearch latency from exceeding Kafka
poll intervals.

Job claims expire. A crashed worker can be retried by another instance.
Temporary dependency failures use bounded exponential backoff with jitter.
Permanent schema, authorization, hash, or version conflicts move to
`DEAD_LETTER` and alert operators without logging source content.

## Deterministic Listing Content

### Canonical source hash

Product Service hashes canonical UTF-8 JSON containing only:

- normalized title;
- normalized description;
- price amount as a decimal string;
- uppercase currency;
- public city;
- public region.

Object keys and decimal rendering are fixed. The agent independently
reconstructs and verifies this hash before embedding.

### Sanitization

The sanitizer is pure and versioned. Initial `listing-sanitizer-v1`:

- applies Unicode NFKC normalization;
- converts line endings and collapses unsafe whitespace;
- removes NUL, bidi overrides, and other disallowed control characters;
- treats HTML/script/markdown-like content as untrusted text, never executable
  instructions;
- keeps ordinary seller wording, including suspicious natural-language
  phrases, so moderation/source ownership remains authoritative;
- applies maximum text and chunk count bounds;
- never adds seller, contact, exact-location, or media data.

Sanitization output is deterministic for the same source version.

### Chunking

Initial `listing-chunker-v1` creates:

1. one `Listing summary` chunk containing name, public city/region, and listed
   price/currency;
2. bounded `Description 1..N` chunks containing the title context plus
   deterministic segments of the approved description.

The implementation uses token-aware boundaries with conservative character
fallbacks, fixed overlap, and a maximum chunk count. Each chunk remains below
both the OpenSearch `8000`-character field limit and provider token limit.

Deterministic chunk ID direction:

```text
sha256(
  sourceType + NUL + sourceId + NUL + sourceVersion + NUL + language +
  NUL + sanitizerVersion + NUL + chunkerVersion + NUL + ordinal +
  NUL + sourceContentHash
)
```

All chunks for a source version carry the canonical source `contentHash`.

## Embedding Provider Contract

Proposed interface:

```text
EmbeddingProvider.embed(texts, correlationId)
    -> vectors in input order
    -> provider/model/dimensions
    -> input-token and request usage
```

OpenAI baseline:

- provider: `openai`;
- model: `text-embedding-3-small`;
- dimensions: `1536`;
- endpoint: Embeddings API through the existing official async Python SDK;
- the request explicitly sets `dimensions=1536`;
- encoding: float;
- bounded batch: implementation default no more than 16 chunks and 32,000
  total input tokens, below provider maximums;
- each input is non-empty and bounded below the documented model limit;
- response count, order indexes, finite values, model identity, and vector
  dimensions are validated before any OpenSearch write;
- provider retries are bounded and restricted to temporary failures;
- authentication, quota, validation, and malformed-response failures are
  classified separately;
- only sanitized public chunk text is sent.

No live provider request runs in normal unit or CI tests. An operator-only
synthetic smoke command may verify a securely configured replacement
credential, but it is not a merge requirement and never uses marketplace
content.

## Indexing And Idempotency

For an active source version:

1. Reject or no-op an older source version using `knowledge_source_state`.
2. Fetch the exact snapshot and verify lifecycle, identity, version, and hash.
3. Sanitize and deterministically chunk.
4. Embed the bounded chunk batch.
5. Validate strict `KnowledgeChunkDocument` objects.
6. Idempotently upsert the new chunks.
7. Invalidate and then physically delete the exact superseded version.
8. Update source state and mark the job successful.

OpenSearch and MySQL are not a distributed transaction. Recovery relies on:

- deterministic chunk IDs;
- idempotent OpenSearch upserts/deletes;
- exact source/version operations;
- monotonic state;
- replayable durable jobs.

If OpenSearch succeeds and the MySQL commit fails, replay repeats the same
documents and completes state safely.

While read and write aliases resolve to different valid generations, live
event operations target both generations. The rebuild loader targets only the
new write generation. A live event is not marked successful until both
required generations succeed.

## Invalidation And Tombstones

For an invalidated source:

1. Validate the event and exact tombstone snapshot.
2. Resolve the exact superseded active version from the tombstone/state.
3. Immediately set `invalidatedAt` on that version in every live generation.
4. Mark source state `TOMBSTONED`.
5. Physically delete the exact version through a retryable deletion worker.
6. Retain safe tombstone metadata long enough to prevent an old event or
   rebuild export from resurrecting content.

Unknown or missing predecessor identity fails closed and enters operator
reconciliation; it must not run a wildcard deletion.

Successful processed-event records are retained for at least the approved
Kafka replay window, initially 90 days. Tombstone state is retained while
resurrection risk exists and at least 90 days after physical deletion.

## Rebuild And Promotion

`02D` adds operator-only commands:

```text
python -m msb_agent_service.knowledge_ingestion status
python -m msb_agent_service.knowledge_ingestion retry --job-id <exact-id>
python -m msb_agent_service.knowledge_ingestion rebuild-listings
python -m msb_agent_service.knowledge_ingestion validate-rebuild --run-id <exact-id>
python -m msb_agent_service.knowledge_ingestion promote --run-id <exact-id>
```

Rebuild flow:

1. Create and validate a new `AI-RAG-01` generation.
2. Start live-event mirroring to old read and new write generations.
3. Page through the authenticated Product Service active export.
4. Process every snapshot with the same sanitizer, chunker, embedding, and
   strict writer used by live jobs.
5. Apply tombstones and catch up all events through the rebuild watermark.
6. Validate zero failed items, source counts, mapping/embedding identity,
   event lag, and oldest pending age.
7. Mark the run `READY_TO_PROMOTE`.
8. Require an explicit operator promotion.
9. Continue live mirroring whenever rollback leaves aliases divergent.

No command accepts a wildcard, arbitrary URL, raw query, or unvalidated index
name. Old physical generation cleanup remains a separate approved operation.

## Configuration Direction

New settings are disabled by default:

```text
AGENT_KNOWLEDGE_INGESTION_ENABLED
AGENT_PRODUCT_SERVICE_URL
AGENT_PRODUCT_SERVICE_TOKEN
AGENT_KAFKA_BOOTSTRAP_SERVERS
AGENT_KAFKA_TOPIC
AGENT_KAFKA_GROUP_ID
AGENT_KAFKA_SECURITY_PROTOCOL
AGENT_KAFKA_USERNAME
AGENT_KAFKA_PASSWORD
AGENT_KNOWLEDGE_WORKER_CONCURRENCY
AGENT_KNOWLEDGE_JOB_MAX_ATTEMPTS
AGENT_KNOWLEDGE_JOB_RETRY_BASE_SECONDS
AGENT_KNOWLEDGE_SOURCE_TIMEOUT_SECONDS
AGENT_KNOWLEDGE_EMBEDDING_BATCH_INPUTS
AGENT_KNOWLEDGE_EMBEDDING_BATCH_TOKENS
```

Existing mapping identity settings must be explicitly configured as:

```text
AGENT_KNOWLEDGE_EMBEDDING_PROVIDER=openai
AGENT_KNOWLEDGE_EMBEDDING_MODEL=text-embedding-3-small
AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS=1536
```

Secrets are excluded from representations, logs, metrics, mappings, events,
and fixtures. Planning does not edit `.env`.

## Security And Privacy

- The agent receives a distinct Product Service token with only source-read
  access; it does not reuse commerce or user credentials.
- Source URLs are configuration-owned; events cannot supply a URL or path.
- Product source responses use strict schemas with unknown fields rejected.
  Kafka event envelopes and version-1 payloads require every contracted field
  but ignore additive unknown fields as required by the shared event
  compatibility contract.
- OpenAI receives only sanitized approved public chunks.
- No prompts, raw source bodies, passages, or embeddings enter logs, traces,
  MySQL ingestion tables, Kafka events, or dead-letter payloads.
- Exact location, contact, seller identity, media, moderation data, and
  storage internals are forbidden by DTOs and negative tests.
- OpenSearch credentials remain restricted to the agent index family.
- The AI agent cannot trigger ingestion, rebuild, promotion, retry, or delete
  commands.

## Failure Behavior

| Failure | Required result |
| --- | --- |
| Kafka unavailable | Listing commits with outbox; publisher retries |
| Agent consumer unavailable | Kafka retains events; lag metrics rise |
| Agent MySQL unavailable | Offset is not committed; event is replayed |
| Product source timeout | Durable job retries; no stale fallback snapshot |
| Source auth/schema/hash failure | Fail closed and dead-letter with safe code |
| Embedding timeout/rate limit | Bounded retry with jitter |
| Embedding auth/quota/validation failure | No OpenSearch write; alert |
| Partial OpenSearch write | Replay deterministic batch; never report success |
| Old/out-of-order event | Idempotent no-op when state already has a newer version |
| Deletion failure | Content remains invalidated and deletion retries |
| Lag over 15 minutes | Mark affected vector knowledge stale for `AI-RAG-03` degradation |

Core listing, marketplace search, and buyer/seller chat never depend on this
pipeline.

## Observability

Structured logs contain safe event/job/source references, versions,
correlation ID, operation, attempt, duration, result, and stable error code.
They exclude source text, vectors, credentials, and raw provider responses.

Metrics use only bounded labels:

- Product outbox unpublished count and oldest age;
- outbox publish success/failure and latency;
- Kafka accepted, duplicate, invalid, and commit totals;
- ingestion jobs by status and oldest pending age;
- source fetch success/failure and latency;
- chunks produced and rejected;
- embedding requests, inputs, tokens, latency, retry, and error class;
- OpenSearch upsert, invalidation, deletion, and partial-failure totals;
- event-to-index propagation lag;
- tombstone/deletion backlog;
- rebuild progress, failures, and readiness-to-promote;
- last successful ingestion timestamp.

Source IDs, listing IDs, event IDs, error messages, and model input are not
metric labels.

## Test Plan

### `02A`

- Flyway migration against MySQL Testcontainers;
- eligible activation stores one immutable snapshot and outbox event in the
  listing transaction;
- transaction rollback leaves neither snapshot nor event;
- every version-changing listing transition emits the correct active or
  invalidated source version;
- exact-version and export API authorization;
- cross-purpose commerce token denial;
- stable cursor pagination;
- no private fields in snapshots/events;
- Kafka outage leaves outbox pending and listing transaction successful;
- duplicate publication is tolerated.

### `02B`

- disabled configuration starts without Kafka/MySQL source work;
- strict event schema and payload hash validation;
- offset commits only after durable enqueue;
- event replay creates one job;
- partition ordering and out-of-order source versions;
- consumer rebalance and clean shutdown;
- job claim expiry and multi-worker isolation;
- Product source authentication, timeouts, `404`, malformed body, and secret
  redaction;
- MySQL migration and repository integration tests.

### `02C`

- golden canonical-hash fixtures shared across Java and Python;
- Unicode, HTML/script, control character, bidi, fake-system-message, and
  prompt-injection fixtures;
- deterministic chunk boundaries, overlap, IDs, ordering, and maximums;
- one summary plus bounded description chunks for the four approved fields;
- no exact location/contact/media/seller/moderation leakage;
- mocked embedding response ordering, token usage, dimensions, NaN/Infinity,
  partial/missing output, and provider error classification;
- no CI test requires an API key or makes a paid request;
- MySQL + mocked source/provider + real OpenSearch `2.15.0` end-to-end test;
- replay after every external-call/commit boundary;
- version replacement and exact invalidation.

### `02D`

- full rebuild with cursor resume;
- concurrent live update and deletion during rebuild;
- dual-generation mirroring;
- crash/restart at export, embed, index, state, and promotion boundaries;
- promotion blocked by failure, lag, mapping mismatch, or incomplete watermark;
- explicit promotion and rollback;
- tombstones prevent resurrection;
- physical deletion retries remain exact and idempotent.

### `02E`

- owner-specific effective-date, language, activation, retirement, conflict,
  and deletion contract tests;
- no agent-owned authoritative policy or category writes.

## Anticipated Files

Planning only; implementation should remain focused within each sub-slice.

`02A` likely changes:

```text
product-service/pom.xml
product-service/src/main/resources/application.properties
product-service/src/main/resources/db/migration/catalog/V<new>__create_listing_knowledge_publication.sql
product-service/src/main/java/.../knowledge/*
product-service/src/test/java/.../knowledge/*
docs/mvp/api-contract.md
docs/mvp/database.md
```

`02B` through `02D` likely change:

```text
agent-service/pyproject.toml
agent-service/README.md
agent-service/db/migration/V1__create_knowledge_ingestion_foundation.sql
agent-service/src/msb_agent_service/config.py
agent-service/src/msb_agent_service/api.py
agent-service/src/msb_agent_service/knowledge_events.py
agent-service/src/msb_agent_service/knowledge_jobs.py
agent-service/src/msb_agent_service/knowledge_source_client.py
agent-service/src/msb_agent_service/knowledge_sanitizer.py
agent-service/src/msb_agent_service/knowledge_chunker.py
agent-service/src/msb_agent_service/embedding_provider.py
agent-service/src/msb_agent_service/knowledge_ingestion.py
agent-service/tests/test_knowledge_*.py
.github/workflows/pull-request-quality.yml
docker-compose.yml
docs/mvp/api-contract.md
docs/mvp/database.md
```

Exact filenames may differ, but source publication, event intake, content
processing, provider integration, index writing, and rebuild orchestration
must remain separate components.

## Implementation Order

1. Implement and verify `AI-RAG-02A`. Complete.
2. Reconcile the exact API/event/database additions into top-level MVP docs.
   Complete.
3. Implement `AI-RAG-02B` with fake source responses and Kafka/MySQL tests.
   Complete.
4. Implement pure sanitization/chunking and cross-language hash fixtures.
   Complete.
5. Implement the embedding adapter with mocked provider tests. Complete.
6. Implement `AI-RAG-02C` listing indexing and live version replacement.
   Complete and activated against the full local listing backlog.
7. Implement `AI-RAG-02D` rebuild, mirroring, tombstones, and promotion gates.
   Complete and locally deployed; paid rebuild/promotion remains an explicit
   operator action.
8. Implement each `AI-RAG-02E` source only after its owning source slice is
   approved and available. `AI-RAG-02E-CATEGORY` is implemented, verified,
   and locally deployed with its category flags disabled; the other three
   sources remain deferred.
9. Run Product Service, agent unit, MySQL, Kafka, real OpenSearch, migration,
   architecture, and secret-scan verification.

## Acceptance Criteria

- Product Service publishes immutable active/tombstone listing source versions
  and durable reference-only events.
- Listing and outbox changes commit atomically.
- The agent commits Kafka offsets only after durable deduplicated intake.
- No external call runs in the Kafka poll loop.
- The initial indexed fields are limited to name, approved description, public
  city/region, and price/currency.
- Exact/private location and other forbidden fields never enter snapshots,
  events, provider input, OpenSearch, MySQL, logs, or tests.
- Sanitization, chunking, content hashes, and chunk IDs are deterministic.
- Embedding output order, model, dimensions, and finite vector values are
  validated.
- Retries and out-of-order events cannot resurrect or downgrade a source.
- Active updates replace exact source versions and deletions use tombstones.
- Rebuild is resumable and live updates continue to reach the served
  generation.
- Promotion is explicit and blocked unless rebuild and lag gates pass.
- The 15-minute propagation objective is measurable.
- No live provider call is required by CI.
- No retriever, customer-facing agent route, session persistence, gateway,
  frontend, LangChain, or Gemini integration is included.

## Completion Report Required Per Sub-Slice

- roadmap/sub-slice IDs completed;
- source, API, event, and database contracts changed;
- forward-only migrations added;
- dependencies and configuration added;
- tests and integration environments run;
- provider calls made, if any;
- outbox, queue, lag, deletion, and rebuild operational status;
- known limitations and deferred source owners;
- confirmation that secrets and forbidden fields were not stored or logged;
- confirmation that unrelated worktree changes were preserved.
