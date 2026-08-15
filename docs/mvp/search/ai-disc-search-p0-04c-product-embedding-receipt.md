# AI-DISC-SEARCH-P0-04C Product Embedding Receipt, Stale Rejection And Rebuild Store

Status: `SOURCE_GREEN_LOCAL_ONLY_WITH_OPEN_GATES`

This slice implements the Product-owned, default-off result boundary following
Product 04A and Agent 04B. It is not release evidence. Product MySQL remains
authoritative; accepted vectors are rebuildable derived data and cannot make a
listing public, eligible, current, available, or recommended.

## Reconciled 04A/04B wire contract

The exact internal route is:

```text
POST /api/v1/internal/agent/discovery/embedding-requests/{requestId}/result
```

The callback body is strict
`MARKETPLACE_LISTING_EMBEDDING_RESULT_V1` and contains the listing version,
document schema/hash, embedding-input schema/hash, the embedding identity, and
exactly 1,536 JSON numbers. The request ID is path-bound. Product's durable 04A
request remains authoritative for the normalizer, redactor, language, listing,
and request identities; Product rebuilds the current canonical source and
checks all of those identities before first acceptance.

Product listing versions are authoritative nonnegative values. P1-17 reconciles
the callback parser and derived receipt/vector-work schema with the zero-based
Product contract, so version `0` callbacks are valid only when they still match
the exact durable 04A request and current public listing state. Negative
versions remain invalid.

Success and exact replay return only:

- `schemaVersion=MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1`
- the same `requestId`
- `outcome=ACCEPTED`

The Product result capability has an independent committed default:

```text
LISTING_SEARCH_EMBEDDING_RESULT_ENABLED=false
```

The feature and constant-time
`X-Agent-Internal-Service-Token` gates run before request parsing, repository
lookup, listing lookup, or vector work.

## Acceptance, replay, and stale behavior

For a first acceptance Product locks the durable 04A request, checks the exact
callback metadata and embedding identity, reloads the listing from Product
MySQL, requires the same public eligible version, rebuilds the canonical 04A
source, and compares every stored document/input/normalizer/redactor/language
identity. Missing requests and deleted, ineligible, changed, or newer listings
return the same non-enumerating stale conflict. Identity and idempotency
conflicts use distinct stable codes.

An already accepted exact vector retry returns the same ACK before current
listing revalidation. This absorbs the Agent's unknown callback outcome without
creating another row. It does not make that receipt current: the rebuild query
still requires the exact current Product listing version, public eligibility,
hashes, normalizer/redactor/language, and embedding identity. A different
canonical vector for the same request returns an idempotency conflict.

Stable errors consumed by Agent 04B are:

- `FEATURE_DISABLED` (404)
- `AGENT_INTERNAL_AUTHENTICATION_REQUIRED` (403)
- `INVALID_REQUEST` (400)
- `LISTING_DISCOVERY_EMBEDDING_STALE` (409)
- `LISTING_DISCOVERY_EMBEDDING_IDENTITY_CONFLICT` (409)
- `LISTING_DISCOVERY_EMBEDDING_IDEMPOTENCY_CONFLICT` (409)
- `LISTING_DISCOVERY_EMBEDDING_UNAVAILABLE` (503)

No body, vector, hash, request/listing identifier, exception message, or
credential is logged or used as a metric label.

## Derived receipt and rebuild contract

Forward migration
`V202607230200__create_listing_discovery_embedding_receipts.sql` adds one
receipt keyed by the durable Product request. Each accepted vector is converted
to exactly 1,536 finite IEEE-754 float32 values in big-endian order. Negative
zero is normalized to positive zero. Product stores exactly 6,144 bytes and a
lowercase SHA-256 hash of those bytes.

Forward migration `V202607240200__allow_zero_version_embedding_receipts.sql`
updates only the receipt and vector-apply work listing-version CHECK
constraints from `>= 1` to `>= 0`; prior migrations and seed data are not
rewritten.

The receipt contains only request/listing/version, schema/hash/normalizer/
redactor/language, embedding identity, vector hash/bytes, and acceptance time.
It contains no JSON callback, provider response, prompt, embedding text,
seller/actor/contact/location/moderation/storage data, or credentials.
Retention remains deferred; this slice adds no destructive purge.

The repository exposes no HTTP rebuild route. Its inactive-generation lookup
returns a receipt only when the current Product listing is still the exact
eligible version and the caller supplies the exact current hash and identity.
Stale derived history therefore cannot be selected for a newer generation.

## Deferred and open gates

- 04C MySQL/Testcontainers migration, concurrency, rollback, and rebuild
  execution
- 04B Agent MySQL execution
- P0-03 and 04A Product disposable-MySQL execution
- the unrelated pre-existing full-Agent customer-service helper error
- Product OpenSearch vector mapping, writes, deletes, rebuild/backfill, alias
  promotion, hybrid ranking, and MySQL-revalidated search
- Agent query embedding and `SEARCH_INDIVIDUAL` hybrid integration
- production latency, relevance, cost, rollout, and browser evidence

No Agent, provider, OpenSearch, Kafka, gateway, frontend, runtime, or
environment activation is part of 04C.
