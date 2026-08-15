# AI-DISC-SEARCH-P0-05C Durable Accepted-Receipt Vector Synchronization

Status: source implemented and locally verified; all controls remain disabled
by default. No shared/runtime OpenSearch target is enabled or changed.

## Ownership and transaction boundary

Product owns accepted embedding receipts, durable vector-apply work, public
listing document construction, target selection, and authoritative MySQL
revalidation. Agent continues to own provider access only. Product does not
call Agent or a provider, and Agent does not access Product MySQL or
OpenSearch.

When `listing.search.vector-sync.enabled` is true, the first successful 04C
receipt insert and its vector-apply work insert occur in the same Product
transaction. The work row contains bounded identifiers, listing version,
lease/retry state, fixed error code, and timestamps only. It does not duplicate
the vector or retain source text, callback bodies, provider payloads, prompts,
actor/seller data, or credentials. Exact callback replay creates neither a
second receipt nor a second work row; a conflicting callback retains the 04C
conflict contract and creates no work.

P1-17 confirms the receipt and vector-apply work schemas accept Product's
zero-based listing versions. Version `0` vector work is still subject to the
same exact request, receipt, current-listing, source-hash, identity, target and
external-version checks as every positive version.

Receipts accepted before synchronization is enabled can be scanned in stable
receipt-sequence order by the bounded Product-internal catch-up service. Each
candidate is revalidated against the exact current request, receipt, public
listing version, canonical hashes, and embedding identity before idempotent
enqueue. This is an internal service method, not an HTTP route.

## Worker and target rules

The leased worker has a separate
`listing.search.vector-sync.worker-enabled` control. Both synchronization
controls and the existing V2 vector client must be valid before the worker can
exist. A compatible V2 target must be available before work is claimed.
Retries are bounded with expiring leases and capped exponential backoff.

For every claim, Product reloads the durable request and receipt, current
listing state, public eligibility, canonical source identity, and target
state. It decodes only the exact 6,144-byte big-endian float32 receipt defined
by 04C, verifies its SHA-256 identity and 1,536 finite values, and constructs a
complete authoritative V2 public document. It writes that document at checked
external version `2 * listingVersion + 2`.

Target routing is fail-closed:

- a compatible V2 stable write generation receives the vector document;
- an exact compatible V2 candidate from the durable P0-05B run also receives
  it;
- identical active/candidate targets are de-duplicated;
- V1 is never sent an embedding;
- when V1 is active and an exact V2 rebuild candidate exists, only the
  candidate is written;
- no compatible V2 target leaves work retryable and incomplete;
- all distinct required targets must succeed, or return a safe idempotent
  external-version result, before the work is marked applied.

The worker takes the same shared Product projection fence used by listing
mutations before it revalidates and writes. This prevents an in-flight vector
write from crossing a promotion boundary with stale target assumptions.

## Stale and privacy safety

A newer/changed listing version, changed canonical hashes, public
ineligibility, deleted listing, identity mismatch, or malformed receipt ends
without an OpenSearch vector write. A newer lexical full-document upsert or
delete at `2 * (listingVersion + 1) + 1` always outranks delayed vector work at
`2 * listingVersion + 2`, so an old vector cannot restore stale content or an
ineligible listing. Accepted stale receipts remain harmless derived history.

Logs and metrics use fixed operation/result labels only. They exclude IDs,
index names, hashes, listing text, vectors, callback bodies, provider details,
and exception messages.

## Release boundary

The following remain false by default:

- `listing.search.vector-sync.enabled`
- `listing.search.vector-sync.worker-enabled`

With them false, receipt behavior is unchanged, no vector work is inserted or
claimed, and no OpenSearch call occurs. P0-06 Product hybrid BM25/vector
ranking and P0-07 Agent query-embedding/tool integration remain deferred.
User-facing hybrid discovery and rollout readiness remain blocked.
