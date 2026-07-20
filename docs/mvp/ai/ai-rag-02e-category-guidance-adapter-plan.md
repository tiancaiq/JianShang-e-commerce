# AI-RAG-02E-CATEGORY Agent Service Category-Guidance Adapter Plan

Status: implemented, verified, and locally deployed on 2026-07-19. Category
intake, processing, and retrieval remain disabled pending an explicit rollout
decision.

Release: V3.

## Goal

Extend the isolated Python Agent Service from listing-only knowledge to the
deployed Product Service `CATEGORY_GUIDANCE` authority without weakening the
existing listing pipeline, source isolation, rebuild safety, or retriever
filters.

This slice consumes manually authored category guidance. It does not create,
edit, approve, translate, or retire authoritative guidance.

## Dependencies And Source Of Truth

This plan depends on:

- `AI-KNOW-01`, implemented and locally deployed;
- `AI-RAG-01`, the strict OpenSearch index and alias foundation;
- `AI-RAG-02B` through `AI-RAG-02D`, the durable listing intake, processor,
  deletion, and rebuild paths;
- `AI-RAG-03`, the listing-only retriever boundary;
- the source ownership, precedence, visibility, citation, and deletion rules
  in `AI-RAG-00`.

The Product Service contract remains authoritative:

- exact source:
  `GET /api/v1/internal/agent/knowledge/category-guidance/{categoryId}/languages/{language}/versions/{sourceVersion}`;
- rebuild export:
  `GET /api/v1/internal/agent/knowledge/category-guidance/export?cursor=&limit=`;
- event topic: `category-guidance-v1`;
- source type: `CATEGORY_GUIDANCE`;
- visibility: `PUBLIC`;
- lifecycle: `ACTIVE` or `INVALIDATED`.

The Agent Service uses the existing dedicated
`X-Agent-Internal-Service-Token`. It never reads Product Service tables.

## Scope

This slice owns:

- strict `category-guidance-v1` Kafka intake;
- durable deduplication and source-type-aware job dispatch;
- exact category-guidance source reads and rebuild export reads;
- canonical source-hash reconstruction and verification;
- deterministic sanitization and chunking;
- embedding and OpenSearch indexing using the existing provider identity;
- monotonic update, invalidation, and exact deletion behavior;
- source-complete rebuild and promotion gates;
- category-scoped, language-scoped retriever support;
- readiness, metrics, logs, and tests for the new source type.

## Non-Goals

This slice does not add:

- policy, safety, or FAQ ingestion;
- AI-authored or AI-translated guidance;
- Product Service source writes;
- a customer-facing agent endpoint, persistence, or frontend behavior;
- a general HTTP, SQL, Kafka, or OpenSearch tool for the model;
- LangChain or Gemini;
- a new embedding model or vector dimension;
- a live provider call merely to verify the implementation.

`MARKETPLACE_POLICY`, `SAFETY_GUIDANCE`, and `MARKETPLACE_FAQ` remain rejected
until their source-owner contracts are implemented.

## Current Listing-Only Gates

The implementation must deliberately replace these current assumptions:

- Kafka configuration permits one listing topic and parser.
- Event and repository types are listing-specific.
- Durable enqueue writes `source_type='LISTING'`.
- The worker accepts only a listing processor.
- Source fetch, sanitizer, chunker, and document builder are listing-specific.
- deletion and rebuild database checks permit only `LISTING`;
- rebuild exports only listings and checks lag for only the listing topic;
- the retriever request, response, filters, and validation permit only
  `LISTING`.

The OpenSearch document model already permits `CATEGORY_GUIDANCE`, and its
strict mapping already supports non-listing documents with no `listingId`.
No index mapping or schema-version change is required.

## Durable Event Contract

Add a strict category-guidance event model while retaining a small normalized
knowledge-event interface for shared durable enqueue behavior.

Required envelope values:

```text
eventType:
  category-guidance.activated
  category-guidance.updated
  category-guidance.invalidated
eventVersion: 1
producer: product-service
aggregateType: category-guidance
aggregateId: {categoryId}
```

Required reference payload:

```json
{
  "sourceType": "CATEGORY_GUIDANCE",
  "sourceId": "01K00000000000000000000002",
  "sourceVersion": "2",
  "knowledgeLifecycle": "ACTIVE",
  "supersedesVersion": "1",
  "language": "en"
}
```

Validation rules:

- `eventId`, `aggregateId`, and `sourceId` follow the existing fixed
  26-character identifier contract;
- `aggregateId` equals `payload.sourceId`;
- source versions are positive decimal integers;
- `supersedesVersion`, when present, is lower than `sourceVersion`;
- `updated` and `invalidated` require `supersedesVersion`;
- `invalidated` requires `INVALIDATED`; activated and updated require
  `ACTIVE`;
- language is normalized lowercase BCP-47 form;
- the Kafka key is exactly `{sourceId}:{language}`;
- additive envelope fields remain forward-compatible, but contracted payload
  fields remain strict.

Event deduplication continues to hash only contracted payload fields. The
normalized event passed to persistence exposes source type, source ID,
version, language, lifecycle, and superseded version without listing-specific
property names.

## Kafka Runtime

Keep the proven listing consumer unchanged and add a separately configured
category-guidance consumer. Both use the shared durable enqueue repository,
but category offsets and deployment can be controlled independently.

Planned settings:

```text
AGENT_CATEGORY_GUIDANCE_INTAKE_ENABLED=false
AGENT_CATEGORY_GUIDANCE_KAFKA_TOPIC=category-guidance-v1
AGENT_CATEGORY_GUIDANCE_KAFKA_GROUP_ID=msb-agent-category-guidance-v1
AGENT_CATEGORY_GUIDANCE_PROCESSING_ENABLED=false
AGENT_CATEGORY_GUIDANCE_RETRIEVAL_ENABLED=false
```

Kafka bootstrap, security, client credentials, poll bounds, and Product
Service credentials remain shared. The topic and group values are validated
as fixed configuration; records cannot select a topic, URL, route, parser, or
consumer group.

Each consumer:

- disables auto-commit;
- validates before persistence;
- inserts the processed-event row and ingestion job atomically;
- commits `offset + 1` only after MySQL commit;
- seeks back on validation, persistence, or commit failure;
- has independent readiness and lag reporting.

The category processing flag is separate from intake. This permits validating
durable intake without initiating paid embedding work. Job claims must accept
an allowlist of enabled source types so the existing listing worker can
continue while category jobs remain pending.

## Agent MySQL Migration

Add forward-only Agent Service Flyway migration `V3`.

Existing `processed_events`, `knowledge_ingestion_jobs`, and
`knowledge_source_state` columns already represent category guidance. Their
repository code must become source-type-neutral; no source text or vector is
added to MySQL.

`V3` must:

1. replace the `knowledge_deletion_jobs` listing-only check with an allowlist
   containing `LISTING` and `CATEGORY_GUIDANCE`;
2. replace the `knowledge_rebuild_runs` listing-only check with
   `LISTING` and `PUBLIC_KNOWLEDGE`, preserving historical listing-only runs;
3. add `knowledge_rebuild_sources`, keyed by `(run_id, source_type)`, with:
   - source type;
   - sanitizer and chunker versions;
   - opaque export cursor and fixed export watermark;
   - export-complete status;
   - expected, processed, skipped, failed, and tombstoned counts;
   - timestamps and a safe last error code;
4. restrict rebuild-source rows initially to `LISTING` and
   `CATEGORY_GUIDANCE`;
5. add indexes for resumable source export and operational status.

New composite runs use `source_type='PUBLIC_KNOWLEDGE'`. The parent run stores
the target generation, prior read generation, shared embedding identity,
aggregate counts, status, and operator identity. Per-source rows are the
authoritative cursor/watermark checkpoints.

The existing parent `chunker_version` field stores a bounded pipeline manifest
identifier for composite runs. It does not pretend that both source types use
one chunker.

## Exact Source Client

Add strict models and allowlisted methods:

```text
fetch_category_guidance(category_id, language, source_version)
fetch_category_guidance_export(cursor, limit)
```

The exact client constructs only the deployed Product Service route, sends
the dedicated internal token, rejects redirects, and validates the complete
response shape.

An active source requires:

- matching source type, category ID, source version, language, lifecycle, and
  superseded version;
- `PUBLIC` visibility;
- timezone-aware `effectiveFrom`;
- lowercase SHA-256 `contentHash`;
- content containing only `categorySlug`, `categoryName`, `title`, and
  `body`.

An invalidated source requires:

- no content, content hash, or effective time;
- timezone-aware `invalidatedAt`;
- the exact superseded version.

The export client enforces the existing `1..200` page bound, opaque cursor,
fixed watermark across pages, active-only latest sources, and strict page
shape.

## Canonical Hash Verification

Hash verification occurs before Agent Service sanitization.

Reproduce the Product Service ordered compact UTF-8 JSON object with these
fields:

```text
categoryId
categorySlug
categoryName
language
title
body
```

Use standard JSON escaping, no insignificant whitespace, and constant-time
digest comparison. A shared Java/Python fixture must cover non-ASCII text,
quotes, backslashes, line breaks, and surrogate-safe Unicode.

A mismatch is terminal `SOURCE_CONTENT_HASH_MISMATCH`. The Agent Service must
not index, retry indefinitely, repair the owner data, or log the source body.

## Sanitization And Prompt-Injection Boundary

Define `CATEGORY_GUIDANCE_SANITIZER_VERSION =
"category-guidance-sanitizer-v1"`.

After hash verification:

- normalize Unicode to NFKC;
- normalize CRLF and CR to LF;
- remove control, formatting, bidi, and surrogate code points except bounded
  newlines and tabs;
- collapse horizontal whitespace;
- preserve at most one blank line between body paragraphs;
- reapply the owner field bounds after normalization;
- fail when any required field becomes empty.

The source is platform-admin-authored but still untrusted model input.
Sanitization is not treated as prompt-injection detection. Retrieved guidance
is passed to later orchestration as quoted source material with provenance,
never concatenated into system instructions or granted tool authority.

## Deterministic Chunking

Define `CATEGORY_GUIDANCE_CHUNKER_VERSION =
"category-guidance-chunker-v1"`.

Reuse the configured 800-token maximum, 80-token overlap, 16-input batch
limit, and 8,000-character document limit. Produce:

1. one metadata chunk:

   ```text
   Category guidance
   Category: {categoryName}
   Category slug: {categorySlug}
   Title: {title}
   ```

2. one or more body chunks:

   ```text
   Category: {categoryName}
   Title: {title}
   Guidance: {bounded body segment}
   ```

Prefer paragraph and sentence boundaries, make progress deterministic, and
fail closed when limits cannot be satisfied.

Refactor deterministic chunk-ID generation to accept the source type,
sanitizer version, and chunker version. The identity remains:

```text
sourceType
sourceId
sourceVersion
language
sanitizerVersion
chunkerVersion
ordinal
contentHash
```

joined with the existing NUL separator and hashed with SHA-256. Existing
listing chunk IDs must remain unchanged.

## Embedding And Index Documents

Use the existing replaceable embedding provider and the immutable deployed
identity:

```text
openai / text-embedding-3-small / 1536
```

CI and normal implementation verification use a fake provider. Any live
backfill or smoke call requires an explicit operator action.

Category documents use:

```text
sourceType: CATEGORY_GUIDANCE
sourceId: categoryId
sourceVersion: exact Product Service version
contentHash: verified owner hash
listingId: omitted
visibility: PUBLIC
language: source language
effectiveFrom: owner timestamp
ordinal, sectionLabel, text, embedding
```

Category name and slug remain bounded passage text rather than new mapping
fields. Exact category isolation uses `sourceId`, so the existing strict index
mapping remains compatible.

## Processing, Updates, And Tombstones

Introduce a source-type dispatcher behind the existing worker:

```text
LISTING            -> ListingKnowledgeProcessor
CATEGORY_GUIDANCE  -> CategoryGuidanceProcessor
everything else    -> SOURCE_TYPE_UNSUPPORTED
```

For an active category source:

1. fetch the exact version;
2. validate the event/source reference;
3. verify the owner hash;
4. compare monotonic source state for `(sourceType, sourceId, language)`;
5. sanitize, chunk, embed, and upsert to every live generation;
6. invalidate and schedule exact deletion of the superseded version;
7. advance source state only after successful index writes.

For a tombstone:

1. fetch and validate the exact invalidated version;
2. invalidate the superseded active version before state advancement;
3. schedule idempotent physical deletion;
4. mark the category/language source state tombstoned.

Duplicate and stale events remain idempotent. A later version wins even when
Kafka records arrive out of order. Conflicting content for one exact version
fails closed. Category invalidation never touches listing documents or
another category/language stream.

## Source-Complete Rebuilds

After category guidance can be retrieved, a listing-only generation is not
safe to promote because it would omit an enabled source type.

Add:

```text
python -m msb_agent_service.knowledge_ingestion \
  rebuild-public-knowledge --operator <operator-id> [--run-id <run-id>]
```

The composite rebuild:

- creates one target generation;
- creates child checkpoints for `LISTING` and `CATEGORY_GUIDANCE`;
- exports each source with its own fixed watermark and cursor;
- uses its source-specific builder;
- rechecks monotonic source state before and after each write;
- mirrors concurrent live changes through the existing divergent-alias path;
- resumes each source independently after a crash.

Promotion requires:

- both source exports complete, including a valid zero-item category export;
- zero failed child items;
- aggregate and per-source counts reconciled;
- mapping and embedding identity compatible;
- ingestion and deletion queues drained for enabled source types;
- zero lag for both listing and category consumer groups;
- target distinct-source counts matching the sum of processed child sources.

The old `rebuild-listings` command remains available for historical recovery,
but promotion of a listing-only run fails with
`REBUILD_SOURCE_SET_INCOMPLETE` whenever category retrieval is enabled.
Rollback continues to move only the read alias and preserves live mirroring.

No rebuild or promotion is automatically run by this slice.

## Retriever Enablement

Refactor the listing-only implementation into a source-aware internal
retriever while preserving the replaceable `KnowledgeRetriever` boundary.
The model cannot choose category IDs, source versions, languages, filters, or
limits.

Trusted runtime context supplies:

- actor user ID;
- subject listing ID and current listing version;
- the subject listing's authoritative category ID;
- effective question time;
- an ordered language allowlist;
- fixed per-source and total passage limits.

The future `getListing` application tool supplies the category ID. User text,
the model, and vector hits cannot replace it.

Add a non-model-facing category-scope resolver backed by Agent Service-owned
`knowledge_source_state`. For the trusted category and allowed languages it
returns only rows where:

- source type is `CATEGORY_GUIDANCE`;
- state is `ACTIVE`;
- `latestIndexedVersion == latestObservedVersion`;
- content hash and embedding identity are present.

This prevents retrieval from selecting a partially processed or known-stale
category version. Kafka lag remains observable eventual consistency; missing
or lagging category guidance is omitted rather than guessed.

Language selection is deterministic:

1. exact normalized locale;
2. its primary language subtag when different;
3. `und`.

No unrelated language is used. Each eligible
`(language, sourceVersion)` pair is an exact filter.

Use one bounded query embedding, then separate fixed OpenSearch searches:

- listing search: the existing exact listing/version filters;
- category search: `sourceType=CATEGORY_GUIDANCE`, exact trusted category
  `sourceId`, exact resolved language/version pairs, `PUBLIC`, effective, and
  non-invalidated.

Separate searches avoid accidentally applying the listing version to category
guidance and permit per-source failure isolation. Initial bounds are at most
five listing passages, three category passages, and eight passages/8,000
characters in total.

Strict result models enforce:

- listing passages retain exact `listingId`;
- category passages omit `listingId`;
- category `sourceId`, version, and language match a resolved trusted scope;
- one source version has one content hash and unique ordinals;
- unknown/private fields, invalid time windows, malformed scores, and
  conflicting results fail closed.

The result preserves exact citation metadata and reports safe per-source
failure codes. Category lookup failure may degrade to valid listing passages;
it must not disable the listing retriever. Category guidance is explanatory
and cannot override current structured listing facts, policy, or safety
guidance.

## Failure Policy

Retry:

- source timeout, transport failure, and Product Service 5xx;
- provider timeout, rate limit, and retryable provider 5xx;
- retryable OpenSearch or MySQL failures.

Dead-letter without repeated provider calls:

- invalid event or message key;
- exact-source 403, 404, non-5xx unexpected status, or schema mismatch;
- event/source reference mismatch;
- canonical hash mismatch;
- unsupported source type;
- unsafe or unchunkable content;
- same-version content/state conflict.

Logs and MySQL retain stable error codes, not response bodies, source text,
embeddings, credentials, or exception payloads.

## Observability And Readiness

Extend existing metrics with low-cardinality `source_type` and bounded result
labels where cardinality remains fixed:

- Kafka intake and commit result;
- processing, source fetch, embedding, and index result;
- job/deletion counts and oldest pending age;
- source state and propagation latency;
- rebuild progress per source type;
- consumer lag per configured topic/group;
- retrieval result, discarded hits, passages, and context characters per
  source type.

Readiness reports listing intake, category intake, processor, deletion worker,
and index status separately. A disabled category feature is `DISABLED`, not
`UNAVAILABLE`. Logs may include source type, source ID, version, language,
event ID, correlation ID, and safe result code; they exclude guidance text.

## Test Plan

### Contract and pure unit tests

- strict category event envelope, lifecycle, version, and message-key tests;
- additive-envelope compatibility and payload-hash fixtures;
- Product Service Java/Python canonical-hash fixtures;
- exact active/tombstone source and export schemas;
- Unicode, controls, bidi, HTML-like text, and prompt-injection fixtures;
- deterministic sanitization, chunk boundaries, overlap, limits, and IDs;
- proof that existing listing chunk IDs do not change.

### Durable intake and persistence

- V3 MySQL 8.4 migration and constraint tests;
- atomic category enqueue and offset behavior;
- duplicate, event-ID conflict, out-of-order, and cross-topic isolation;
- source-type-filtered claims while category processing is disabled;
- category deletion scheduling and leased retry behavior;
- no source bodies, passage text, vectors, or credentials in MySQL.

### Processing and OpenSearch

- exact route/token construction and wrong-purpose token failure;
- event/source mismatch and hash mismatch fail closed before embedding;
- mocked embedding ordering, limits, dimensions, and safe failures;
- real OpenSearch category upsert, update, invalidation, and exact deletion;
- cross-category, cross-language, and cross-source deletion isolation;
- live mirroring to divergent read/write generations.

### Rebuild and operations

- independent listing/category cursors and watermarks;
- zero-item category export;
- crash and resume at both source checkpoints;
- concurrent update/tombstone during each export;
- promotion blocked by missing source, count mismatch, dead letter, deletion
  backlog, mapping mismatch, or lag in either group;
- listing-only promotion blocked after category retrieval activation;
- composite promotion and rollback with synthetic embeddings.

### Retriever

- trusted category scope cannot be supplied by user/model text;
- exact category/version/language/effective filters;
- no cross-category or arbitrary-language passage;
- category passage requires absent `listingId`;
- stale, tombstoned, failed, or partially indexed scope is omitted;
- listing retrieval survives category-source failure;
- total/per-source context bounds and citation metadata;
- real OpenSearch relevance and isolation with synthetic vectors.

CI makes no live OpenAI request.

## Delivery Order

1. Add V3 migration and MySQL migration tests.
2. Generalize normalized events, durable enqueue, claims, and dispatch without
   changing listing behavior.
3. Add the separately gated category Kafka intake.
4. Add strict category exact/export source clients and cross-language hash
   fixtures.
5. Add category sanitizer, chunker, builder, and mocked-provider tests.
6. Add active/update/tombstone processing and real OpenSearch tests.
7. Make rebuild runs source-complete and add two-group promotion gates.
8. Add the category scope resolver and category-scoped retriever path.
9. Update Compose/runtime configuration, readiness, metrics, and operator
   documentation.
10. Deploy with all three category flags disabled and verify listing behavior.
11. Enable category intake, then processing only through an explicit operator
    decision; reconcile topic lag, jobs, state, deletions, and index counts.
12. Enable category retrieval only after source/index reconciliation.

## Rollout And Rollback

Initial deployment performs no source publication, embedding backfill,
rebuild, promotion, or live provider smoke.

Safe rollout:

1. migrate Agent MySQL;
2. deploy code with category intake, processing, and retrieval disabled;
3. verify existing listing intake, processing, deletion, rebuild status, and
   retrieval;
4. enable intake and confirm durable offsets;
5. obtain operator approval before enabling processing if active events would
   incur embedding cost;
6. reconcile every category source state and indexed version;
7. enable retrieval.

Rollback disables category retrieval first, then processing and intake.
Listing behavior continues. Indexed category documents may remain as inert
derived data because fixed listing filters cannot retrieve them; exact cleanup
is a separate controlled operation.

## Acceptance Criteria

- Category events are durably and independently consumed with no body on
  Kafka.
- Exact owner content is fetched through the allowlisted authenticated route.
- The Agent Service independently verifies the owner hash before sanitation.
- Category chunks and IDs are deterministic and existing listing IDs remain
  stable.
- Only the existing approved embedding identity is accepted.
- Updates, tombstones, retries, and out-of-order events cannot resurrect an
  older category version.
- Deletion is exact to category, language, and source version.
- A promotable index generation contains every enabled source type.
- Retrieval uses only the trusted subject listing category and exact resolved
  source versions.
- Cross-category, cross-language, stale, invalidated, and malformed passages
  are excluded.
- Category failure does not break listing retrieval or core marketplace
  behavior.
- Policy, safety, and FAQ sources remain rejected.
- No customer endpoint, AI-authored authority, secret, or private field is
  introduced.
- CI passes without a live provider call.

## Completion Report Required

When implemented, report:

- `AI-RAG-02E-CATEGORY` as the completed slice;
- files and contracts changed;
- V3 migration details;
- Product Service, Python, MySQL, Kafka, and OpenSearch test results;
- category flags and deployed readiness;
- topic lag, job/state/deletion/index reconciliation;
- whether any live embedding or rebuild was run and its token usage;
- known limitations and deferred policy/safety/FAQ owners;
- confirmation that unrelated worktree changes were preserved.

## Implementation Report

`AI-RAG-02E-CATEGORY` is complete. The Agent Service now has a separately
gated category-guidance Kafka intake, exact/export Product Service clients,
independent hash verification, deterministic sanitization and chunking,
monotonic processing and tombstones, source-complete public rebuild tracking,
and a category/version/language-scoped retriever. Listing behavior remains
independent and all category flags default to disabled.

Persistence:

- Agent migration `V3__add_category_guidance_knowledge_source.sql` extends the
  existing source allowlists and adds `knowledge_rebuild_sources`;
- a `PUBLIC_KNOWLEDGE` rebuild must contain exactly `LISTING` and
  `CATEGORY_GUIDANCE` child sources with independent cursors and watermarks;
- the migration was applied successfully to the local Agent MySQL schema.

Contracts and runtime:

- category events remain reference-only and use the
  `{categoryId}:{language}` Kafka key;
- content is fetched only from the authenticated exact/export owner routes;
- category ingestion, processing, and retrieval have separate flags, topics,
  consumer groups, readiness, and worker claims;
- category documents cannot carry `listingId`, and listing retrieval cannot
  select category documents;
- promotion verifies every enabled source set and both Kafka consumer groups.

Verification:

- Product Service owner contract suite: 9 passed, comprising 7 category
  integration tests and 2 outbox publisher tests;
- Agent Service unit/default suite: 109 passed, 9 skipped;
- focused MySQL 8.4 Testcontainers suite: 5 passed;
- real OpenSearch integration suite: 4 passed with synthetic vectors;
- Python compilation and Compose configuration validation passed;
- local Agent health is `UP`, readiness is `READY`, and category readiness is
  `DISABLED`;
- the local `category-guidance-v1` topic is healthy with one partition,
  replication factor one, and end offset zero.

No category event was consumed, no embedding request was made, and no paid
rebuild, promotion, or provider smoke was run. Policy, safety, and FAQ sources
remain rejected until their authoritative owners exist. No customer-service
endpoint or orchestration was added. The normal base-image rebuild was blocked
by temporary Docker Hub connectivity; the already-local image was updated
offline and the resulting Agent container is healthy. Unrelated worktree
changes were preserved.
