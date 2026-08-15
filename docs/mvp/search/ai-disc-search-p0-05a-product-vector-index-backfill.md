# AI-DISC-SEARCH-P0-05A Product Vector Index Schema And Inactive Backfill Foundation

Status: `SOURCE_GREEN_LOCAL_ONLY_INACTIVE_VALIDATED`

## Boundary

Product Service owns the V2 public-listing vector mapping, inactive physical
generations, authoritative MySQL snapshot, accepted derived receipt selection,
and offline validation. Agent Service remains the only embedding-provider
owner. Product does not call a provider, Agent does not access Product MySQL or
OpenSearch, and the Agent knowledge index remains separate.

This slice does not promote either stable listing-search alias. It does not
change the active V1 synchronization worker, expose hybrid search, or claim
runtime/release readiness. The feature gate
`listing.search.vector-backfill.enabled` is `false` by default.

## Immutable V2 mapping

Fresh inactive indexes use the physical family
`marketplace-listings-v2-g<UTC timestamp>` and schema identity
`marketplace-public-listing-v2-vector`. The strict mapping preserves the V1
public lexical and structured allowlist and adds:

- authoritative `listingVersion`;
- document/input schema and SHA-256 identity;
- normalizer, redactor, and literal language `und` identity;
- `openai/text-embedding-3-small/1536` metadata and bounded embedding state;
- optional `embedding` as a 1536-dimensional HNSW `knn_vector`, Lucene engine,
  cosine similarity, `m=16`, and `ef_construction=100`.

The mapping excludes `embedding` from ordinary `_source` responses. A mapping
or `_meta` mismatch fails closed. The implementation never updates an existing
vector mapping in place.

## Authoritative backfill

The source reader takes one bounded MySQL `REPEATABLE_READ` snapshot, pages
eligible public listings in stable listing-ID order, and uses the existing
public search-document allowlist. It never reads an owner identity, contact,
private/exact location, moderation/audit field, storage key, prompt, or provider
payload into the projection.

For an eligible individual listing, the reader rebuilds the canonical 04A
document/input hashes and queries the 04C rebuild store for the exact current
listing version, hashes, normalizer, redactor, language, model, and dimensions.
Only an exact 6144-byte big-endian float32 receipt with 1536 finite components
and its matching canonical SHA-256 hash receives a vector. Missing or rejected
receipts remain lexical-only. Business public listings are lexical-only because
their embedding-request contract is not approved.

Product listing versions are nonnegative. External OpenSearch versions reserve
`2 * listingVersion + 1` for lexical state and `2 * listingVersion + 2` for its
exact vector-bearing state. Version zero therefore maps to positive versions 1
and 2. Negative and overflowing source versions fail before indexing.

## Inactive validation result

The internal result schema is
`MARKETPLACE_LISTING_VECTOR_BACKFILL_RESULT_V1`. Success is classified only as
`INACTIVE_VALIDATED` and reports bounded document, vector, lexical-only,
rejected-receipt, and elapsed-time values. It never reports documents, text,
hashes, listing IDs, or vectors.

Validation requires:

- exact code-owned mapping and metadata;
- zero documents before the backfill;
- total indexed count equal to the authoritative snapshot count;
- vector count equal to the exact accepted receipts attached;
- stable active read/write alias responses before and after success/failure.

A failed generation is deleted only when the current operation created that
unique inactive generation and the cleanup flag permits it. No alias mutation
operation exists in the V2 backfill client.

## Deferred P0-05B contract

P0-02 has a known snapshot-to-promotion concurrent-write window. P0-05B must
define and implement a Product-owned watermark/catch-up/promotion protocol
before any V2 generation can become live:

1. capture a durable Product projection-work watermark before the snapshot;
2. backfill and validate an inactive V2 generation;
3. replay all eligible upsert/delete work after that watermark with checked
   `2V+1/2V+2` ordering, including vector invalidation;
4. hold or recheck a final bounded watermark until no mutation is missing;
5. atomically move read and write aliases only after mapping, count, lag, and
   catch-up gates pass;
6. retain a rollback target and fail without alias movement on any ambiguity.

P0-05B, live vector synchronization, Product hybrid search/RRF, Agent query
embedding/tool integration, runtime activation, and rollout evidence remain
deferred and default-off.
