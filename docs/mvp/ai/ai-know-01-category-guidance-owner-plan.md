# AI-KNOW-01 Category Guidance Owner Plan

Status: implemented, verified, and deployed locally on 2026-07-19.

Release: V3.

## Goal

Create the Product Service owner contract for human-authored public category
buying guidance so `CATEGORY_GUIDANCE` can later be ingested by
`AI-RAG-02E`.

This slice owns authoritative category-guidance records, admin publication,
exact/export reads, and durable reference events. It does not add Agent
Service ingestion, embeddings, OpenSearch writes, retrieval, model calls, or
generated guidance content.

## Contract Decision

The first contract supports exactly one guidance source per
`(categoryId, language)`.

- `sourceType` is always `CATEGORY_GUIDANCE`.
- `sourceId` is the existing Product Service category ID.
- `sourceVersion` is a positive, strictly increasing integer scoped to
  `(categoryId, language)`.
- An `ACTIVE` version contains an immutable snapshot of category slug/name,
  guidance title/body, language, content hash, and effective time.
- An `INVALIDATED` version is a newer immutable tombstone with no body or
  content hash and identifies the exact version it supersedes.
- Visibility is always `PUBLIC`.

Multiple articles for one category, permission-scoped guidance, scheduled
future activation, rich HTML, attachments, and AI-authored source text are
outside this slice. A future contract may introduce a distinct stable source
key if multiple articles become necessary.

## Ownership And Authorization

Product Service owns:

- categories and category state;
- `category_guidance_versions`;
- the immutable source-version sequence;
- platform-admin publish and retire commands;
- exact-version and watermark-stable export reads;
- transactional outbox records and reference-event publication.

Only an authenticated platform admin confirmed through the existing Auth
Service authorization contract may publish or retire guidance. The server
derives the admin user ID and correlation ID. Neither is accepted in the
request body.

Agent Service receives read access only through the existing
`X-Agent-Internal-Service-Token`. It never accesses the Product Service
schema. AI cannot create, edit, activate, retire, or approve authoritative
guidance.

## Persistence Plan

Add forward-only Product Service Flyway migration:

```text
V202607191600__create_category_guidance_publication.sql
```

Create `category_guidance_versions` with:

- `category_id`;
- normalized `language`;
- positive `source_version`;
- nullable `supersedes_version`;
- `ACTIVE` or `INVALIDATED` lifecycle;
- `PUBLIC` visibility;
- immutable category slug and name snapshots for active rows;
- title and plain-text body for active rows;
- lowercase SHA-256 `content_hash` for active rows;
- nullable `effective_from`;
- nullable `invalidated_at`;
- activating or invalidating admin user ID;
- correlation ID;
- creation time.

Primary key:

```text
(category_id, language, source_version)
```

Indexes support:

- latest-version lookup and row locking by category/language;
- latest active export as of a fixed watermark;
- creation-time audit and export scans.

The same-service category foreign key is allowed. Existing migrations are
not rewritten.

The row-shape check requires:

- active rows to contain snapshots, title, body, hash, and effective time
  with no invalidation time;
- invalidated rows to contain a superseded version and invalidation time with
  no snapshots, title, body, or hash;
- `supersedes_version < source_version`.

Historical active versions remain immutable. Publication or retirement always
inserts a new row; it never edits the prior version.

The existing generic `outbox_events` table and bounded publisher claim path
are reused. A second publisher must not race the existing outbox publisher.

## Canonical Content

Guidance is manually authored plain text. Initial validation:

- normalized language tag: 2 through 35 characters;
- title: 1 through 180 characters after trimming;
- body: 1 through 12,000 characters after trimming;
- reject unsupported control characters and HTML/script payloads;
- normalize line endings but preserve meaningful paragraph boundaries.

The cross-language SHA-256 contract hashes a deterministic UTF-8 JSON object
with no insignificant whitespace, standard JSON string escaping, and fields
in this exact order:

```text
categoryId
categorySlug
categoryName
language
title
body
```

Agent Service must later reproduce this canonical form before indexing.
Authoritative text remains untrusted model input even though a platform admin
authored it.

## Admin API Plan

All routes use the external `/api/v1` prefix and require platform-admin
authorization.

```text
GET  /api/v1/admin/categories/{categoryId}/guidance/{language}
GET  /api/v1/admin/categories/{categoryId}/guidance/{language}/versions?cursor=&limit=
POST /api/v1/admin/categories/{categoryId}/guidance/{language}/versions
POST /api/v1/admin/categories/{categoryId}/guidance/{language}/retire
```

Publish requires:

- active category;
- required `If-Match` containing the latest source version, or `0` when no
  version exists;
- strict body containing only `title` and `body`;
- row lock on the category/language sequence;
- atomic active version and outbox insertion.

Publishing after an active version creates the next version and sets
`supersedesVersion` to the prior latest version. Reactivating after a
tombstone also creates the next version.

Retirement requires the current active version in `If-Match`, inserts the next
`INVALIDATED` version, and supersedes that active version. Repeated or stale
commands fail with `409 CATEGORY_GUIDANCE_VERSION_CONFLICT` and create no
row or event.

Responses return an `ETag` containing the latest source version. Unknown
categories return `404`; inactive categories cannot publish new active
guidance.

The minimum admin UI adds a category/language guidance editor under the admin
surface with current version, title/body validation, explicit publish/retire
confirmation, and version-conflict recovery. It never generates content with
AI.

## Internal Agent Read Plan

```text
GET /api/v1/internal/agent/knowledge/category-guidance/{categoryId}/languages/{language}/versions/{sourceVersion}
GET /api/v1/internal/agent/knowledge/category-guidance/export?cursor=&limit=
```

Both require the dedicated Agent Service token and use constant-time
comparison.

The exact route never falls back to latest. Active response direction:

```json
{
  "sourceType": "CATEGORY_GUIDANCE",
  "sourceId": "01K00000000000000000000002",
  "sourceVersion": "1",
  "supersedesVersion": null,
  "lifecycle": "ACTIVE",
  "visibility": "PUBLIC",
  "language": "en",
  "effectiveFrom": "2026-07-19T08:00:00Z",
  "contentHash": "lowercase-sha256",
  "content": {
    "categorySlug": "electronics",
    "categoryName": "Electronics",
    "title": "Buying used electronics",
    "body": "Check the model, included accessories, and visible condition."
  }
}
```

An invalidated response omits content/hash/effective time and includes
`invalidatedAt` plus the exact `supersedesVersion`.

Export uses the existing `1..200` bounded cursor convention and a fixed
watermark. It returns only the greatest version per category/language when
that version is `ACTIVE`. It excludes inactive categories and any source
whose greatest version is invalidated.

## Durable Event Plan

Topic:

```text
category-guidance-v1
```

Event types:

```text
category-guidance.activated
category-guidance.updated
category-guidance.invalidated
```

Kafka message key:

```text
{categoryId}:{language}
```

Version-1 payload is reference-only:

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

The source-version insert and outbox insert commit in one transaction.
Payloads never contain guidance bodies, category snapshots, admin identity, or
credentials. Agent Service later deduplicates by event ID.

## Category Lifecycle Rule

Category deactivation must insert one newer `INVALIDATED` guidance version for
every active language in the same category-status transaction. Reactivation
does not automatically republish old guidance; a platform admin must publish
a new version.

There is no category-status mutation API today. The implementation must place
this rule in a reusable Product Service domain method and test it so a later
category-admin slice cannot bypass it.

## Tests Required

- migration structure, checks, indexes, and same-service foreign key;
- first publication and sequential version creation;
- immutable historical rows;
- retirement through a newer tombstone version;
- reactivation after retirement;
- stale/missing `If-Match` conflict behavior;
- inactive and unknown category rejection;
- admin authorization and non-admin denial;
- title/body/language/control/HTML validation;
- canonical hash fixtures shared with the future Python adapter;
- source row plus outbox transaction rollback;
- reference-only event payload and ordering key;
- exact-version token authorization, wrong-purpose token denial, and no
  latest fallback;
- stable watermark export, cursor bounds, active-latest selection, and
  invalidated/inactive exclusion;
- category-deactivation invalidation rule;
- frontend editor authorization, validation, confirmation, and conflict state.

## Observability

Add low-cardinality metrics for publish/retire result, outbox backlog and
publication result, internal exact/export result, and authorization failure.
Structured logs include operation, category ID, language, source version,
admin user ID, correlation ID, result, and safe error code. They exclude
guidance body, credentials, access tokens, and response payloads.

## Delivery Order

1. Add migration and migration tests.
2. Add repository, canonical hasher, and strict domain models.
3. Add admin authorization and publish/retire services.
4. Reuse the transactional outbox publisher for the new topic.
5. Add exact-version and stable export services.
6. Add controllers and standard error mappings.
7. Update OpenAPI/API client types.
8. Add the minimum admin guidance editor.
9. Run Product Service, frontend, MySQL, Kafka-publisher, authorization, and
   contract tests.
10. Deploy Product Service and create the Kafka topic without publishing
    production guidance automatically.

## Completion Boundary

`AI-KNOW-01` is complete when Product Service can safely publish, retire,
export, and event-reference manually authored category guidance.

It does not complete `AI-RAG-02E`. The next dependent slice is
`AI-RAG-02E-CATEGORY`, which adds Agent Service intake, exact-source fetch,
canonical verification, sanitization/chunking, embedding, indexing,
invalidation, rebuild integration, and retriever enablement for
`CATEGORY_GUIDANCE`.

## Implementation Report

Completed on 2026-07-19:

- added forward-only Product Service Flyway
  `V202607191600__create_category_guidance_publication.sql`;
- added immutable category/language publication, retirement, history, exact,
  export, category-deactivation invalidation, canonical hashing, and
  reference-only outbox behavior;
- reused the existing bounded transactional outbox publisher for
  `category-guidance-v1`;
- added platform-admin APIs, Agent-token APIs, standard error mappings,
  gateway routing, low-cardinality metrics, and safe structured logs;
- added the manual admin editor at `/admin/category-guidance` with validation,
  confirmation, optimistic conflict reload, and no AI generation;
- created the local `category-guidance-v1` Kafka topic without seeding or
  publishing guidance content;
- deployed healthy Product Service and API gateway containers, applied
  migration `202607191600`, built the frontend image, and verified the
  existing local frontend server responds at port `4200`.

Verification:

- Product Service compile/package: passed;
- `CategoryGuidanceIntegrationTests`: 7 passed;
- shared outbox publisher unit tests: 2 passed;
- `ListingDomainFoundationMigrationTests`: 8 passed;
- API gateway regression tests: 29 passed;
- focused Angular route/layout/service/editor tests: 19 passed;
- Angular production build: passed;
- Product Service health: `UP`;
- gateway route without an admin session: `401`, confirming protected routing;
- Kafka topic: one partition, replication factor one, in-sync replica one.

No live OpenAI request was made. The dependent `AI-RAG-02E-CATEGORY` adapter
was subsequently implemented and locally deployed with its rollout flags
disabled; no authoritative category guidance was generated automatically.
