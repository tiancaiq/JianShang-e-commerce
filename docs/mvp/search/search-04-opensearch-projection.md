# SEARCH-04 OpenSearch Projection

Status: complete.

## Scope

SEARCH-04 adds an OpenSearch-backed derived projection for approved public
listing search.

It does not change the public search response shape and does not make
OpenSearch authoritative for listing visibility. MySQL remains the source of
truth.

Implemented endpoints remain:

```text
GET /api/v1/public/marketplace/listings/search
GET /api/v1/public/stores/listings/search
```

An admin rebuild endpoint is added:

```text
POST /api/v1/admin/search/listings/rebuild
```

## Behavior

- `listing.search.engine=mysql` is the default.
- `listing.search.engine=opensearch` enables the OpenSearch read path.
- OpenSearch search returns candidate listing IDs only.
- Product service reloads those IDs from MySQL before returning public cards,
  preserving final visibility checks and safe public projection rules.
- When durable projection synchronization is explicitly enabled, Product writes
  one versioned synchronization intent in the same MySQL transaction as each
  public-search-relevant listing transition.
- If OpenSearch search is enabled and unavailable, public search returns
  `503 LISTING_SEARCH_UNAVAILABLE`.
- Listing detail remains MySQL-backed.

## Configuration

```properties
LISTING_SEARCH_ENGINE=mysql
OPENSEARCH_URL=http://localhost:9201
OPENSEARCH_LISTING_INDEX=marketplace-listings
OPENSEARCH_LISTING_WRITE_ALIAS=marketplace-listings-write
OPENSEARCH_LISTING_PHYSICAL_INDEX=marketplace-listings-v1
OPENSEARCH_CONNECT_TIMEOUT=PT1S
OPENSEARCH_REQUEST_TIMEOUT=PT3S
OPENSEARCH_INITIALIZE_INDEX=true
LISTING_SEARCH_PROJECTION_SYNC_ENABLED=false
LISTING_SEARCH_PROJECTION_SYNC_BATCH_SIZE=50
LISTING_SEARCH_PROJECTION_SYNC_POLL_INTERVAL_MS=5000
LISTING_SEARCH_PROJECTION_SYNC_CLAIM_SECONDS=60
LISTING_SEARCH_PROJECTION_SYNC_MAX_ATTEMPTS=20
LISTING_SEARCH_PROJECTION_SYNC_RETRY_BASE=PT2S
LISTING_SEARCH_PROJECTION_SYNC_RETRY_MAX=PT5M
```

Local compose includes OpenSearch on host port `9201` and OpenSearch
Dashboards on `5602`, avoiding the existing Elasticsearch/Kibana ports.

## Projection Fields

The index stores only safe searchable public fields:

- listing ID
- seller type
- category ID, slug, and name
- title and description
- a denormalized public `searchText` field used by BM25
- condition
- price amount and currency
- public city/county keys
- published timestamp
- public availability and primary public image URL

Application queries use the stable `marketplace-listings` read alias. Live
projection upserts and deletes use the separate, exact-one-target
`marketplace-listings-write` alias. The initial physical index is
`marketplace-listings-v1`. Its strict mapping carries deterministic `_meta`
identity for the public-listing projection schema and deliberately contains no
embedding/vector field.

An admin rebuild reads the complete authoritative public-listing set from
MySQL, creates a fresh immutable generation named from the
`marketplace-listings-v1-g<UTC timestamp>` family, bulk indexes only that
snapshot, refreshes it, and validates its schema and document count. A single
OpenSearch aliases request then moves both read and write aliases. Mapping,
bulk, count, or promotion failures leave the previous read generation
addressable; a post-promotion validation failure attempts a bounded alias
rollback. Previous physical generations are retained for operational rollback
and are not queried once their aliases move.

Index initialization and rebuild wait for bounded OpenSearch yellow health.
Both aliases must resolve to exactly one compatible shared generation. An
explicit OpenSearch engine selection fails closed when health, alias, mapping,
or search validation fails; it does not silently fall back to MySQL. Product
still reloads candidate IDs from authoritative MySQL before returning public
results.

## Durable synchronization

`AI-DISC-SEARCH-P0-03` adds the Product-owned
`listing_search_projection_work` ledger. It stores only a generated work ID,
opaque listing ID, aggregate version, `UPSERT`/`DELETE` operation, fixed
processing state, retry/lease metadata, fixed error code, and timestamps. It
contains no listing content, owner identity, contact data, moderation reason,
media storage reference, prompt, or other PII.

The Product listing transaction writes the intent only when
`listing.search.projection-sync.enabled=true`. Publish, approval, relist, and
approved public-field edits create `UPSERT` intent. Pause, close, trade
completion, seller edits that return a listing to draft, review submission,
rejection, changes requested, admin removal, and missing/hard-deleted rows
create or resolve as `DELETE`.

A bounded scheduled worker leases committed work with `SKIP LOCKED`. Expired
leases are restart-recoverable. Before each write it reloads the current
listing version and public eligibility from Product MySQL. Older work is
completed as superseded without an OpenSearch call. Current work uses checked
lexical external version `2 * listingVersion + 1` through the P0-02 write alias;
version conflicts are idempotent stale/duplicate outcomes,
so a delayed upsert cannot resurrect a newer deletion. OpenSearch outages use
capped exponential retry. Exhausted or malformed work becomes a safe terminal
record and requires operational repair/rebuild. Logs and metrics use fixed
operation/result/error labels and never listing or actor identifiers.

Synchronization remains false by default. Ordinary MySQL marketplace behavior
therefore makes no synchronization-table or OpenSearch call. Before enabling
it on an environment that accumulated listing changes while disabled,
operators must first run the authoritative admin rebuild, verify both aliases
and document count, and only then enable the worker. The rebuild remains the
repair path because OpenSearch is derived and never authoritative.

## Product discovery embedding source (`AI-DISC-SEARCH-P0-04A`)

Product owns the canonical public discovery document and records one durable,
version-bound embedding request plus a reference-only outbox event in the same
transaction as an eligible individual-listing mutation. Request creation is
controlled by `listing.search.embedding.request-enabled=false`; exact source
reads are independently controlled by
`listing.search.embedding.source-enabled=false`. Disabled request creation
does not query discovery source tables, create an outbox row, call OpenSearch,
or affect ordinary marketplace behavior.

The canonical embedding input is UTF-8/NFKC text with fixed ordered sections
`TITLE`, `CATEGORY` name/slug, and `DESCRIPTION`. It removes controls and bidi
overrides, collapses whitespace, preserves case and punctuation, and applies
the versioned `PUBLIC_CONTACT_REDACTION_V1` email, phone-like string, and URL
redactor. Title is bounded to 160 characters, category name to 160, category
slug to 120, description to 5,000, and final text to 8,000. Language is the
literal `und`. Price, condition, availability, location, seller identity/type,
images, publication time, negotiability, quantity, moderation/internal data,
contact data, storage data, and transaction notices are not embedded.

The Product request row stores only request/event/listing identity, listing
version, fixed schema and normalizer/redactor identities, document/input
SHA-256 hashes, `openai/text-embedding-3-small/1536` identity, fixed request
state, and timestamp. Raw listing text, prompt, vector, provider payload,
seller/contact information, and storage references are not stored. The event
`listing.discovery.embedding-requested` version 1 carries the same reference
metadata and no content. The generic Product transactional outbox remains the
publisher boundary; this slice adds no Agent consumer or dispatcher behavior.

The internal route is request-bound:

```text
GET /api/v1/internal/agent/discovery/embedding-requests/{requestId}/source
```

It requires the constant-time `X-Agent-Internal-Service-Token` boundary. The
feature and authentication gates run before request lookup. Product resolves
the stored request, then recomputes the canonical source only while the exact
referenced listing version remains current, active, approved, individual, and
public. Missing, stale, changed-hash, deleted, and ineligible requests all use
the same non-enumerating
`404 LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND`. A newer listing version is
never substituted.

`AI-DISC-SEARCH-P0-04B` Agent embedding consumption and
`AI-DISC-SEARCH-P0-04C` Product vector receipt are implemented source-only and
default-off. Their Product and Agent disposable-MySQL gates are green.
`AI-DISC-SEARCH-P0-05A` adds a separately gated Product-owned V2 vector
mapping and deterministic inactive backfill. Its disposable MySQL and
OpenSearch 2.15 validation gates are green, but the generated index is only
`INACTIVE_VALIDATED`. `AI-DISC-SEARCH-P0-05B` adds the false-default durable
run, shared/exclusive MySQL fence, dual-write, watermark catch-up, atomic
two-alias promotion, bounded rollback, and crash reconciliation. No shared or
runtime alias has been promoted and runtime/release readiness is not claimed.
`AI-DISC-SEARCH-P0-05C` adds separately gated durable accepted-receipt
vector-apply work, exact-current catch-up, leased V2 target writes, and stale
resurrection protection. No shared/runtime vector synchronization is enabled.
`AI-DISC-SEARCH-P0-06` adds the independently default-off internal Product
hybrid endpoint, fixed BM25 and filtered k-NN branches, deterministic unweighted
RRF, and mandatory Product MySQL revalidation. `AI-DISC-SEARCH-P0-07` adds the
default-off Agent query-embedding and typed hybrid-tool client without direct
Agent OpenSearch access. `AI-DISC-SEARCH-P0-08` adds the independently
default-off, platform-admin-only Product operator API for P0-05B prepare,
status, catch-up, fenced promotion, and recovery. No shared/runtime rebuild or
promotion has been executed. `AI-DISC-SEARCH-P0-09` adds a separate
default-off platform-admin embedding-request backfill for approved public
individual listings that predate 04A. It captures a finite Product-ID
watermark, processes one leased page per command, and reuses the exact 04A
request/outbox transaction. Completion does not imply that Agent jobs,
embeddings, receipts, vectors, or a V2 index are complete.
`AI-DISC-SEARCH-P0-10A` adds a separate false-default Angular platform-admin
control surface for the unchanged legacy V1 rebuild and P0-09
start/status/one-page-resume contracts. It uses the existing BFF session and
CSRF interceptor, performs no automatic command or replay, and intentionally
does not execute runtime maintenance by itself. `AI-DISC-SEARCH-P0-10B`
extends the same page with strict P0-08 V2 prepare/status/catch-up/promote and
recover controls. The UI displays only bounded Product status data and enables
V2 commands solely from Product-derived `canCatchUp`, `canPromote`, and
`canRecover` booleans. Runtime and browser acceptance remain blocked.

The 04C callback accepts only the exact current 04A request and canonical
Product source identity. It stores 1,536 finite values as exactly 6,144
big-endian float32 bytes plus their SHA-256 hash. Exact retry is idempotent;
stale, identity-mismatched, and conflicting results use distinct stable errors.
No OpenSearch write occurs. A repository-only rebuild lookup requires the exact
current eligible listing version, hashes, and identity, so stale retained
receipts cannot enter a newer index generation. See
`docs/mvp/search/ai-disc-search-p0-04c-product-embedding-receipt.md`.
`AI-DISC-SEARCH-STAB-P1-17` reconciles the callback parser, receipt CHECK, and
vector-work CHECK with Product's zero-based listing-version contract: version
`0` is valid when it matches the exact current request/listing identity, while
negative versions remain invalid.

The 05A mapping identity is `marketplace-public-listing-v2-vector`. It uses
1536-dimensional HNSW/Lucene cosine vectors (`m=16`,
`ef_construction=100`) excluded from ordinary `_source`. Backfill reads a
bounded repeatable-read Product MySQL snapshot and attaches only exact-current
04C receipts. Product listing versions are zero-based; lexical/vector external
versions are checked `2V+1`/`2V+2`, keeping OpenSearch versions positive and
ensuring the next lexical version outranks the prior vector.
Missing, stale, malformed, or mismatched receipts cannot attach a vector. See
`docs/mvp/search/ai-disc-search-p0-05a-product-vector-index-backfill.md`.
The fenced promotion contract is recorded in
`docs/mvp/search/ai-disc-search-p0-05b-product-projection-fence-promotion.md`.
The durable accepted-receipt worker contract is recorded in
`docs/mvp/search/ai-disc-search-p0-05c-durable-vector-synchronization.md`.
The Product-only hybrid retrieval contract is recorded in
`docs/mvp/search/ai-disc-search-p0-06-product-hybrid-search.md`.
The controlled operator boundary is recorded in
`docs/mvp/search/ai-disc-search-p0-08-controlled-v2-operator-boundary.md`.
The existing-listing request backfill contract is recorded in
`docs/mvp/search/ai-disc-search-p0-09-existing-listing-embedding-request-backfill.md`.
The default-off admin control-surface contract is recorded in
`docs/mvp/search/ai-disc-search-p0-10a-admin-index-request-backfill-ui.md`.

It does not store owner private IDs in returned payloads, internal moderation
state, media bucket/key, private contact data, exact individual locations,
orders, payment, inventory, or checkout data.

## Deferred

- Runtime activation of accepted-receipt synchronization.
- Runtime activation and authenticated browser acceptance of Agent hybrid use.
- Automated retention/deletion policy for superseded physical generations.
- Store-slug scoped storefront pages.
- Suggestions, typo tolerance, analytics, and learned or personalized ranking.
