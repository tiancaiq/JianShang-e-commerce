# AI-DISC-SEARCH-P0-08 Controlled V2 Search Rebuild Operator Boundary

Status: source-only/default-off implementation. No runtime rebuild or alias
promotion has been executed.

## Boundary

Product exposes a separate platform-admin-only namespace for the existing
P0-05B durable workflow:

```text
POST /api/v1/admin/search/listings/vector-rebuilds
GET  /api/v1/admin/search/listings/vector-rebuilds/{runId}
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/promote
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/recover
```

The old `POST /api/v1/admin/search/listings/rebuild` BM25 operation is unchanged.
V2 commands accept no body. Product allocates the candidate generation and
derives all alias, schema, watermark, count, and operator identity data. A
canonical Crockford ULID run ID is the only path input.
`AI-DISC-SEARCH-STAB-P1-19` clarifies the transport boundary: fixed-length
zero-byte requests and empty chunked requests are valid bodyless commands;
any decoded payload byte is rejected with `LISTING_INVALID_REQUEST` before
service invocation. Product does not buffer, log, store, or expose command
payload bytes.

The status DTO schema is
`MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2`. It exposes only fixed durable
state/outcome/error codes, safe generation roles, bounded counts and
timestamps, the fixed `marketplace-public-listing-v2-vector` schema identity,
and the Product-derived booleans `canCatchUp`, `canPromote`, and `canRecover`.
It never exposes physical index names, aliases, documents, listing/seller IDs,
hashes, vectors, queries, credentials, prompts, or OpenSearch responses.

Those booleans are status snapshots, not client-side state-machine rules.
Product derives them from the same durable run, exact alias, and projection
work preconditions used by command preflight:

- `canCatchUp` requires `CATCHING_UP` with both stable aliases still on the
  recorded previous generation.
- `canPromote` additionally requires zero unresolved durable projection work.
  `catchUpWorkCount` remains a bounded processed-work count and is never a lag
  or readiness signal.
- `canRecover` is true only when exact alias state makes recovery meaningful:
  an unknown promoted outcome, or a durable `PROMOTION_FENCED` or
  `ROLLBACK_REQUIRED` state whose aliases resolve exactly to the recorded
  previous or candidate generation. Healthy `PROMOTED` state is not
  recoverable.

Every command re-evaluates its eligibility. Promotion still repeats the
zero-unresolved-work and alias preconditions while holding the exclusive
Product database fence before any alias mutation, so a stale `canPromote=true`
snapshot cannot authorize promotion. Recovery either records the candidate
aliases as promoted or restores previous aliases to `CATCHING_UP`; it never
blindly replays an alias operation.

`AI-DISC-SEARCH-P0-10B` is the matching Angular operator surface. It consumes
only this V2 response schema, displays bounded state/count/timestamp/role data,
and enables catch-up, promote, or recover only from these Product-derived
booleans. The UI must not infer readiness from `catchUpWorkCount`, state names,
or local sequencing; every command remains an explicit bodyless POST that the
Product service rechecks.

## Authorization, gates, and audit

Product resolves the current bearer actor and requires the existing
`PLATFORM_ADMIN` authorization. Authentication occurs before command/read
feature gates and before run lookup. The independent defaults are:

```text
listing.search.operator.commands-enabled=false
listing.search.operator.status-enabled=false
```

P0-03 synchronization, P0-05A backfill, and P0-05B rebuild/promotion gates
must also be enabled by a later controlled runtime task. No startup action is
added.

Migration `V202607240000__create_listing_search_operator_audit.sql` adds only
one immutable protected audit table. Each authenticated prepare, catch-up,
promote, or recover outcome records the admin user ID under the protected
audit contract, action, optional run reference, fixed from/to state, fixed
outcome/error code, correlation ID, and timestamp. It stores no listing
content, vector, index name, request body, credential, or exception text.
There is no automatic purge until a Product-wide protected-admin-audit
retention policy is approved; the slice does not invent destructive cleanup.

## Idempotency and safety

The existing database single-active-run constraint remains authoritative.
Prepare checks the durable active run before candidate allocation. Catch-up and
recovery reuse the P0-05B state machine. A durable `PROMOTED` run makes promote
an exact replay with no alias request; concurrent promotions serialize through
the existing exclusive Product fence and row lock. No process-local locks,
sleeps, direct SQL operation, or arbitrary OpenSearch controls are introduced.

Release/runtime readiness remains blocked until a separate local runtime task
enables the required gates, executes the operator sequence, verifies V2 alias
and vector readiness, enables Product hybrid and Agent query-embedding/tool
gates in rollback-safe order, and completes authenticated browser acceptance.

`AI-DISC-SEARCH-STAB-P1-10` reconciles this boundary with Product's authoritative
zero-based optimistic-lock contract. The untouched Walnut demo seed at listing
version `0` maps to positive OpenSearch lexical/vector versions 1 and 2, so the
disposable operator flow can prepare and promote the real seeded catalog
without a data migration. This removes the source-level seed compatibility
blocker; runtime activation and authenticated acceptance remain separately
blocked until an explicitly authorized task enables and exercises the gates.
