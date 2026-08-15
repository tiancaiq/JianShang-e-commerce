# AI-DISC-SEARCH-P0-09 Existing Public Listing Embedding-Request Backfill

Status: source-only/default-off implementation. No Agent/provider call, vector
write, index build, alias promotion, or runtime activation has been executed.

## Durable Product boundary

Product captures the greatest currently eligible public individual listing ID
when a run is created. That ID is a finite upper-bound watermark. Each command
processes at most one bounded page in ascending Product listing-ID order. The
run stores only its generated ID, upper watermark, last processed cursor,
fixed state, safe counts, lease/retry metadata, fixed error code, and
timestamps. It stores no listing content, hashes, request/event IDs, seller or
actor identity, contact/private location data, prompt, vector, provider
payload, or credential.

States are `PENDING`, `RUNNING`, `FAILED`, and `COMPLETED`. One generated
active slot permits at most one non-completed run. A bounded lease lets a
later resume reclaim an interrupted `RUNNING` page. The durable cursor moves
only after every per-listing transaction in that page commits. If processing
stops first, resume safely replays the page through the existing 04A unique
request identity.

Each candidate is reloaded in a separate Product transaction. Product
rechecks that it is a current `ACTIVE`, `APPROVED`, public `INDIVIDUAL`
listing, then calls the canonical 04A
`createForCurrentEligibleVersion` path. That path alone owns normalization,
redaction, hashes, request identity, and the unchanged reference-only
`listing.discovery.embedding-requested` version-1 outbox event. Request and
event commit atomically. Exact existing identity is counted as already
present; concurrent live/backfill creation still produces one request/event.
Rows that become missing or ineligible are skipped. A stale exact-version
request remains harmless because the 04A source route revalidates the current
version and hashes before any Agent/provider work.

Migration
`V202607240100__create_listing_embedding_request_backfill.sql` adds the run
table and extends the protected P0-08 audit table with a typed backfill-run
reference and `EMBEDDING_START`/`EMBEDDING_RESUME` actions. Audit rows contain
the authenticated platform-admin reference, bounded run/state/outcome/error
data, correlation ID, and timestamp only.

## Operator API and defaults

```text
POST /api/v1/admin/search/listings/embedding-request-backfills
GET  /api/v1/admin/search/listings/embedding-request-backfills/{runId}
POST /api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume
```

Commands accept no decoded request payload. HTTP framing is not payload:
zero-byte fixed-length requests and empty chunked transfers are accepted even
when `Transfer-Encoding` is present. Product reads only enough of the decoded
servlet stream to distinguish EOF from the first byte; any decoded byte is
rejected with the existing `LISTING_INVALID_REQUEST` response before command
service invocation. Bodies are never buffered, logged, stored, or reflected.
Product derives the watermark, cursor, page size, topic, embedding identity,
and actor. The existing Product platform-admin authorization runs before
feature gates or run lookup. Status omits the watermark/cursor and exposes only
fixed schema/state/outcome/error values, bounded counts, and timestamps.

All defaults remain false:

```text
listing.search.embedding.backfill.commands-enabled=false
listing.search.embedding.backfill.status-enabled=false
```

04A request creation must also be enabled before a run can start. Default-off
commands perform no listing enumeration, request/outbox write, audit write, or
external call. One command processes one page; operators inspect status and
repeat `resume` until `COMPLETED`. Completion means every eligible row through
the captured watermark was created, already present, or safely skipped with
no unresolved failure. It does not mean Agent jobs, embeddings, receipts,
vectors, or a V2 generation are complete.

## Controlled local acceptance order

The later runtime task owns all flags, credentials, and commands:

1. Enable Product 04A request/source plus the existing transactional-outbox
   publisher.
2. Enable Agent 04B intake/worker/provider with the P1-14 lifespan-composed
   runtime and the global kill switch available.
3. Enable Product 04C result receipt and P0-05C vector synchronization worker.
4. Enable the P0-09 command/status gates, `POST` a backfill, then call `resume`
   until its status is `COMPLETED`; independently wait for Product outbox,
   Agent embedding, callback, and Product vector queues to drain.
5. Use P0-08 to prepare, inspect, catch up, and promote the V2 generation.
6. Enable Product P0-06 hybrid search.
7. Enable Agent P0-07 query embedding/hybrid retrieval.
8. Run authenticated browser acceptance.
9. Roll back in reverse order, using the Agent/global kill switches first.

This order is operational guidance only. This source slice does not enable or
execute any step and does not claim runtime or rollout readiness.
