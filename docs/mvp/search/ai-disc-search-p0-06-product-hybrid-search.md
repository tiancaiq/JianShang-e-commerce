# AI-DISC-SEARCH-P0-06 Product Hybrid Search

Status: source implemented, local verification required before any activation.

## Boundary

Product Service owns the internal hybrid retrieval endpoint:

```text
POST /api/v1/internal/agent/marketplace/listings/hybrid-search
```

The route requires the existing constant-time Agent service token and is
independently controlled by `listing.search.hybrid.enabled=false`. It accepts
schema `MARKETPLACE_HYBRID_SEARCH_V1`, one bounded plain-text query, the exact
`openai/text-embedding-3-small/1536` query vector identity, and only approved
individual-listing filters. It accepts no actor, seller, alias, index, DSL,
scoring weight, prompt history, URL, or runtime-control field.

`AI-DISC-CS-P1-05` also defines a separate Product-owned broad availability
probe:

```text
GET /api/v1/internal/agent/marketplace/listings/availability?category={category}&limit=1
```

It checks the same Agent service token before reading Product MySQL and returns
the exact `MARKETPLACE_AVAILABILITY_PROBE_V1` count contract for active,
approved, quantity-positive individual inventory. It accepts no detailed
filters, vector, actor, or ranking controls and does not call OpenSearch or the
embedding provider. It is used only behind the existing default-off Agent
Discovery capability; no second activation flag is introduced. Hybrid ranking
and revalidation semantics are unchanged.

Product never calls the embedding provider. Agent query embedding and
`SEARCH_INDIVIDUAL` integration remain deferred to P0-07. The existing public
marketplace search path and Agent knowledge/RAG index are unchanged.

## Retrieval and ranking

Product validates that the stable read alias resolves to exactly one compatible
`marketplace-public-listing-v2-vector` generation. It then runs two independent,
bounded branches:

- BM25 over fixed Product public text fields with fixed boosts;
- filtered 1536-dimensional Lucene/cosine k-NN.

Both branches use the same server-owned structured filters and return at most
60 IDs. Raw BM25/cosine scores are never normalized, persisted, logged, or
returned. Product fuses one-based branch ranks with unweighted RRF:

```text
1 / (60 + lexicalRank) + 1 / (60 + vectorRank)
```

Fusion accepts at most 120 branch entries, retains at most 80 unique IDs, and
orders by fused score descending, best branch rank ascending, then listing ID
ascending. There is no personalization, sponsorship, popularity, engagement,
or learned ranking.

## Authoritative revalidation

Every fused ID is batch-loaded from Product MySQL. Product rechecks current
individual/public/active/approved eligibility, availability, category,
condition, price/currency, and public city/region. Missing, stale, ineligible,
or filter-mismatched candidates are removed without substitution. Returned
title, category facts, condition, money, public location, availability, public
primary image, publication time, and individual-transaction notice come from
current Product tables, never OpenSearch.

The response exposes only fixed rank provenance (`HYBRID`, `LEXICAL_ONLY`, or
`VECTOR_ONLY`, fixed matched-branch values and reason code). It contains no raw
score, vector, query, owner/business identity, contact, exact address,
moderation state/reason, storage key, quantity, prompt, or provider payload.

`AI-DISC-AGENT-V2-INVENTORY-FACETS-02` adds an opt-in, backward-compatible
response projection. Requests omitting `responseSchemaVersion` still receive
`MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1`. A strict request for
`MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2` additionally receives a `discovery`
object with a normalized query/category label, explicit
`RESULTS_AVAILABLE|CATEGORY_UNAVAILABLE` reason, one bounded relevant-match
count, and bounded subtype, condition, price-band, and public-location facets.

`AI-DISC-AGENT-V2-RESULTS-FIRST-03` adds opt-in
`MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3`; V1 and V2 response shapes remain
unchanged for rolling updates. V3 defines `totalMatches` as the bounded
current MySQL-revalidated candidates that pass request filters and
`relevantMatchCount` as the subset that also passes Product's main-product and
accessory rejection rules. V3 additively returns `exactMatchCount` and
`relatedMatchCount`; all meaningful normalized query terms must match current
public listing text for an exact match. Exact multi-term matches sort before
partial alternatives while existing fused order remains the tie-breaker.
Confidence uses only those bounded counts,
lexical-branch presence, and degraded-mode state. It does not expose or persist
raw retrieval scores. V3 adds `LOW_RELEVANCE` and `HIGH|MEDIUM|LOW` confidence;
V1 ranking and revalidation semantics remain unchanged.

`AI-DISC-AGENT-V2-MULTICONCEPT-RERANK-05` adds opt-in V4. The lexical branch
keeps AND semantics within each bounded original/synonym phrase, while the
vector branch remains recall-oriented. After fusion, Product loads current
MySQL facts including public description and applies a bounded concept model:
title matches carry the strongest weight, normalized category fields are
structured evidence, description matches are weaker, missing core concepts are
penalized/rejected, and known incompatible broad types are rejected. Product
sorts compatible `HIGH` matches before `MEDIUM` alternatives and retains fused
order only as the final tie-breaker. It returns fixed concept labels and match
classes, never a score, and does not fill top-K with `LOW` candidates.

Current Product rows do not yet own normalized product-type, brand/model, or
compatibility attributes. The forward boundary is an asynchronous,
version-stamped Product enrichment projection populated from listing
publication/change events and rebuilt independently of request traffic.
Hybrid retrieval tolerates absent/stale enrichment and falls back to the
authoritative title/category/description fields used here; no request calls an
enrichment provider synchronously. This design is decoupled from V4 and
requires no migration or index rebuild in this slice.

Facet inputs are only current MySQL-revalidated candidates that passed the
existing Product-owned hybrid retrieval and request filters. Product removes
known main-product accessory matches (for example chair wheels/covers/cushions
for a chair query) and normalizes supported subtype synonyms such as desk/task
chairs and office seating to `Office Chair`. An exact subtype query is not
satisfied by a different chair subtype. Both counts are bounded by the existing
80-candidate fusion safety ceiling and are not unbounded catalog estimates. Raw
RRF/branch scores remain internal and are never returned.

## Degradation and rollout

`listing.search.hybrid.single-branch-fallback-enabled=false` is a separate
default. With it false, either technical branch failure returns
`503 MARKETPLACE_HYBRID_SEARCH_UNAVAILABLE`. When explicitly enabled, exactly
one healthy branch may return fixed degraded metadata. Zero hits are not a
technical failure. Alias, mapping, or embedding identity mismatch always fails
closed.

Stable errors are `FEATURE_DISABLED`,
`AGENT_INTERNAL_AUTHENTICATION_REQUIRED`, `INVALID_REQUEST`,
`MARKETPLACE_HYBRID_SEARCH_IDENTITY_MISMATCH`, and
`MARKETPLACE_HYBRID_SEARCH_UNAVAILABLE`. Logs and metrics use only fixed result,
branch, count-bucket, and timing dimensions.

All flags remain false. P0-07 Agent query embedding/tool integration, runtime
activation, production latency/relevance evidence, and authenticated browser
acceptance remain blocked and are not implied by offline Product verification.
