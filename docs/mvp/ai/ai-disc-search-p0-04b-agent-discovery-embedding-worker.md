# AI-DISC-SEARCH-P0-04B Agent Discovery Document Embedding Worker

Status: `SOURCE_GREEN_LOCAL_ONLY`; runtime composition stabilized by
`AI-DISC-SEARCH-STAB-P1-14`, with production-worker compatibility corrected by
`AI-DISC-SEARCH-STAB-P1-15`.

This slice implements the Agent-owned, default-off processing boundary between
Product's `AI-DISC-SEARCH-P0-04A` reference event/source and Product's
`AI-DISC-SEARCH-P0-04C` vector receipt contract. It does not authorize release.
Runtime flag activation, event drain evidence, V2 promotion, hybrid retrieval,
production evidence, and browser acceptance remain separate gates.

## Ownership

- Product owns listing truth, canonical discovery text and hashes, the request
  event, the exact source endpoint, future vector receipt, OpenSearch, ranking,
  and final MySQL revalidation.
- Agent owns provider access and the durable processing job only.
- Agent never queries Product MySQL or Product OpenSearch.
- Product never receives provider credentials or calls Agent.
- Discovery document jobs and vectors are not written to Agent RAG knowledge
  jobs, state, or indexes.

## Exact Product event

The dedicated topic is `listing-discovery-embedding-request-v1`. The strict
record is the shipped Product envelope:

- `eventType=listing.discovery.embedding-requested`
- `eventVersion=1`
- `producer=product-service`
- `aggregateType=listing`
- message key, aggregate ID, and payload listing ID must agree
- canonical event/request/listing ULIDs, nonnegative Product listing version, UTC
  timestamp, bounded correlation ID, lowercase SHA-256 hashes
- literal document/input/normalizer/redactor/language identities
- literal `openai/text-embedding-3-small/1536`

Unknown fields and incompatible records are rejected before persistence.
Event and request IDs are both unique. Exact replays are idempotent; conflicting
event or request replays are terminal-safe.

## Durable job and privacy

Agent Flyway V9 creates `discovery_embedding_jobs`, a table separate from the
RAG knowledge ingestion tables. It stores only:

- event, request, listing, version, schema, hash, and embedding identities
- event time, bounded correlation ID, and canonical event hash
- pending/processing/retry/succeeded/dead-letter state
- attempts, next attempt, expiring lease, safe error code, and timestamps

It never stores the event body, canonical embedding text, prompt, vector,
provider response, actor/seller identity, or credentials.

The worker uses bounded database claims, lease-expiry restart recovery,
deterministic capped exponential backoff, terminal safe classifications, and
at-least-once callback delivery. Cancellation leaves the lease to expire. A job
becomes `SUCCEEDED` only after an exact Product acknowledgement.

## Source, provider, and callback flow

1. Fetch only:
   `GET /api/v1/internal/agent/discovery/embedding-requests/{requestId}/source`.
2. Verify every returned request/listing/version/schema/hash/redaction/language
   and embedding identity against the durable event.
3. Treat embedding text as untrusted data. It remains in memory only and is
   never logged, traced, or persisted.
4. Call the existing replaceable `EmbeddingProvider` exactly once with one
   input. Require one ordered 1,536-value finite vector for
   `openai/text-embedding-3-small`.
5. Submit through the typed Product receipt boundary:
   `POST /api/v1/internal/agent/discovery/embedding-requests/{requestId}/result`.
   The strict `MARKETPLACE_LISTING_EMBEDDING_RESULT_V1` body contains only the
   version/hash/identity contract and exactly 1,536 finite values.
6. Require
   `MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1`, the same request ID, and
   `outcome=ACCEPTED` before completing the job.

Product 04C owns the live receipt endpoint and vector store. Agent tests use
fake transports unless a separately authorized runtime acceptance task enables
the Product endpoint.

Source not-found/stale is terminal. Source or callback timeout, transport
failure, feature unavailability, and 503 are retryable. Callback stale,
identity-conflict, and idempotency-conflict outcomes are distinct terminal
classifications. Upstream bodies and vectors are never included in errors or
logs.

## Default-off gates and readiness

All are false by default:

- `AGENT_DISCOVERY_EMBEDDING_INTAKE_ENABLED`
- `AGENT_DISCOVERY_EMBEDDING_WORKER_ENABLED`
- `AGENT_DISCOVERY_EMBEDDING_PROVIDER_ENABLED`

`AGENT_DISCOVERY_EMBEDDING_KILL_SWITCH_ENABLED` takes precedence. Disabled
intake does not parse or persist records; disabled worker/provider or an engaged
kill switch does not claim jobs or call Product/provider/callback.

`AI-DISC-SEARCH-STAB-P1-14` composes the already-implemented runtime in the
FastAPI lifespan only when these independent flags allow it. Startup validates
the V9 job schema, creates bounded low-cardinality metrics, Product source and
callback clients, the existing OpenAI-compatible embedding provider without a
startup provider call, Kafka intake, and the durable worker. Intake starts
before the worker; shutdown stops the worker before intake and then closes
provider, Product client, and MySQL resources.

`/ready` reports `discoveryDocumentEmbedding=DISABLED|READY|UNAVAILABLE|DEFERRED`.
The field is disabled by default and does not claim release readiness. Disabled
or kill-switched startup creates no Product, Kafka, provider, persistence, or
network work.

`AI-DISC-SEARCH-STAB-P1-15` fixes the first production-worker drain failure
without replaying runtime data. The production runtime injects
`DiscoveryEmbeddingMetrics` into the shared `OpenAIEmbeddingProvider`; that
metrics object now implements the provider's `record_embedding(...)` protocol
and maps provider outcomes to fixed low-cardinality labels before the callback
step. P1-15 also aligns Agent's strict 04B event/source/result validators with
Product's authoritative nonnegative listing-version contract, including
version `0` seed listings. Existing dead-letter jobs and published broker
events remain durable and unrepaired until a separate, supported recovery or
requeue task is approved.

`AI-DISC-SEARCH-STAB-P1-16` adds that source-side recovery contract without
executing it. Agent V10 relaxes the durable job check to Product's
nonnegative listing-version contract and adds
`discovery_embedding_recovery_commands`, a bounded idempotency/audit table for
local operator recovery. The local command is not a browser/API route and is
behind `AGENT_DISCOVERY_EMBEDDING_RECOVERY_ENABLED=false` by default. It may
only requeue jobs that are already `DEAD_LETTER` with
`MAX_ATTEMPTS_EXHAUSTED`, have the approved schema/hash/provider identity, and
have no active lease. The operator must provide a printable recovery key and
the exact expected eligible count; count mismatch, mixed state, active work, or
key/hash conflict fails closed. Exact replay returns the stored result. The
command resets scheduling/lease/attempt state only enough to make those same
jobs claimable, while preserving request/event identity and aggregate exhausted
failure evidence in the recovery audit row.

`AI-DISC-SEARCH-STAB-P1-18` extends the same local command with explicit mode
`PRODUCT_VERSION_ZERO_CALLBACK_CONTRACT_REPAIRED_V1` for the repaired Product
P1-17 callback contract. Agent V11 broadens the recovery audit CHECK only enough
to record `CALLBACK_INVALID_RESPONSE` as original evidence. This mode may
recover only `DEAD_LETTER` jobs whose last safe error is exactly
`CALLBACK_INVALID_RESPONSE`, whose listing version is exactly `0`, whose
schema/provider/model/dimension identity is approved, and whose lease is not
active. The command hash includes this mode and expected count, so it cannot be
replayed as the older `MAX_ATTEMPTS_EXHAUSTED` recovery. It preserves the
existing MAX mode and does not execute any runtime recovery by itself.

Kafka intake continues to commit offsets only after durable enqueue succeeds.
Invalid schema or MySQL persistence failure seeks back to the same offset and
does not commit. Therefore Product version-0 events rejected by the pre-P1-16
Agent path should replay on consumer restart if the preserved consumer group
offset was not advanced by another runtime action. If runtime evidence shows
those offsets were already committed or compacted away, a separate Product or
broker replay decision is required; P1-16 does not fabricate Product events.

## Deferred

- runtime flag activation and draining existing Product outbox events
- execution of the P1-16/P1-18 recovery command against preserved dead-letter
  jobs
- Product or broker replay if runtime proves the 137 no-job events were
  already committed/lost despite the source-level seek/no-commit contract
- Product V2 generation promotion in shared/local runtime
- production latency, quality, cost, rollout, and browser evidence
