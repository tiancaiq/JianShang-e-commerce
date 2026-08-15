# AI-DISC-SEARCH-P0-05B Product Projection Fence, Catch-Up And Atomic Alias Promotion

Status: source implemented, local verification required before any environment
activation.

## Boundary

Product MySQL remains authoritative. A rebuild is one durable Product-owned run
that records the exact previous read/write generation, exact V2 candidate,
listing-work sequence watermark, accepted-receipt sequence watermark, bounded
counts, fixed state, and fixed error code. The run stores no listing content,
vector, actor/seller identity, prompt, credential, or private data. At most one
run can be active.

The controls are independent and false by default:

- `listing.search.projection-sync.enabled`
- `listing.search.vector-backfill.enabled`
- `listing.search.promotion.rebuild-enabled`
- `listing.search.promotion.promotion-enabled`

Ordinary listing mutations do not query the coordination or rebuild tables and
make no OpenSearch call while projection synchronization is disabled. No public
or gateway route starts a rebuild.

## Fence and ordering

When synchronization is enabled, the listing transaction takes a shared
`FOR SHARE` lock on the singleton `PUBLIC_LISTING` coordination row before it
records projection intent. The final promotion transaction takes `FOR UPDATE`
on the same row. The exclusive lock blocks new mutation-intent commits while
already committed work is drained and the aliases are moved.

Lexical upsert/delete state uses checked external version
`2 * listingVersion + 1`. An exact accepted vector uses
`2 * listingVersion + 2`. Version zero maps to positive OpenSearch versions 1
and 2; negative and overflowing versions are rejected.
During `DUAL_WRITE`, `BACKFILLING`, `CATCHING_UP`, and `PROMOTION_FENCED`, the
P0-03 worker must apply each current authoritative lexical state to both the
stable write alias and the exact durable candidate generation before completing
the work item. A partial write remains retryable. V1 never receives a vector.

## Backfill, catch-up, and promotion

The candidate is created as an empty, unaliased V2 physical generation. The run
activates dual-write before the repeatable-read backfill snapshot. Backfill uses
only Product public fields and exact 04C receipts at or below the captured
receipt watermark. Post-watermark receipts are reported as missing vectors and
cannot attach stale vectors.

Catch-up replays every durable work row after the run's start sequence,
including rows already completed by the worker, by re-reading current Product
truth. External versions make an older snapshot harmless. Promotion requires
zero unresolved work, exact current document/vector counts through the receipt
watermark, compatible V2 mapping metadata, an unaliased candidate, and both
stable aliases resolving to the recorded previous generation.

Under the exclusive database fence, one OpenSearch aliases request moves both
the stable read and write aliases. Product persists `PROMOTED` only after exact
post-move validation. A failed/unknown outcome gets one bounded inverse aliases
request when exact alias state permits it. Recovery compares durable state with
the exact two alias targets; it never blindly repeats promotion.

The previous generation is retained. Physical-generation deletion, live
receipt-to-index synchronization, Product hybrid BM25/vector ranking, RRF,
query embeddings, and Agent `SEARCH_INDIVIDUAL` integration remain deferred.
Hybrid discovery and rollout readiness remain blocked.
