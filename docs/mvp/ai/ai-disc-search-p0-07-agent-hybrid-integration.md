# AI-DISC-SEARCH-P0-07 Agent Hybrid Discovery Integration

Status: source-green local-only; all runtime gates remain false and rollout is
blocked.

## Boundary

Marketplace Discovery now has an optional Agent-owned query-embedding path
behind both AGENT_DISCOVERY_HYBRID_RETRIEVAL_ENABLED=false and
AGENT_DISCOVERY_QUERY_EMBEDDING_ENABLED=false. The existing global Discovery
API, orchestration, Product-tool, provider, persistence and kill-switch rules
still apply first.

When the hybrid path is disabled, the existing public Product search adapter is
unchanged and no query embedding or Product hybrid request occurs. When it is
explicitly enabled with every prerequisite, Agent normalizes one search phrase,
requests exactly one openai/text-embedding-3-small/1536 embedding through the
replaceable provider, and calls only:

POST /api/v1/internal/agent/marketplace/listings/hybrid-search

Agent does not query Product OpenSearch or either service's database. The
listing-document embedding worker and the Agent knowledge/RAG index remain
separate.

## Tool and grounding contract

SEARCH_INDIVIDUAL remains the only candidate-search tool. Its arguments are
bounded query text plus category, condition, price/currency, city/county and
limit. Product enforces INDIVIDUAL, AVAILABLE and RELEVANCE. Price bounds
require an explicit currency. After `AI-DISC-SEARCH-STAB-P1-24`, Agent
deterministically supplies `USD` only when the normalized user turn contains an
explicit dollar-denominated price phrase and the model omitted currency, so
queries such as "under $100" do not fail before query embedding. Product rank is
preserved.

Product P0-06 results are the only candidate authority in hybrid mode.
GET_LISTING remains required before rendering current listing facts. Product
listing identifiers are opaque 26-character Crockford public IDs
(`^[0-9A-HJKMNP-TV-Z]{26}$`) and may begin with `8-Z`; Agent-owned session,
invocation, message and event identifiers keep their separate Agent validation
path. Product listing version is a nonnegative Product version, so version `0`
is accepted. Fixed RRF provenance is retained in the existing invocation
tool-call source references. The public live/history DTO is unchanged, so strict
Angular history parsing remains compatible. No query, vector, prompt,
description or private Product field is persisted in those references.

The deterministic final guard returns three to five recommendations when
available. It never pads: two verified results use the existing COMPARE wire
shape, one uses the existing DETAIL wire shape, and zero uses NO_RESULTS.
COMPARE, DETAIL, exclusions and replay behavior remain compatible.
After `AI-DISC-SEARCH-STAB-P1-43`, specific product-type searches also pass a
deterministic post-detail relevance guard before recommendation persistence.
The guard uses repository-owned normalization, location/generic-token removal
and a small explicit synonym table; `QUERY` coverage is recorded only when the
current Product title/category/description facts contain the requested specific
type or its approved synonym. Broad needs such as sleep-comfort discovery are
not forced through product-type matching and may still clarify or recommend
semantically grounded comfort items.

Clarification is capped to one concise question per turn and is permitted only
when one missing high-value constraint makes a useful search unreasonable.
Ordinary comfort-product suggestions must not become diagnosis, treatment or
outcome claims.

## Failures and readiness

Provider/query-embedding failure and Product disabled, authentication,
invalid-request, identity-mismatch, unavailable and malformed-response failures
retain distinct fixed internal classifications. After
`AI-DISC-SEARCH-STAB-P1-40`, Agent persistence accepts the bounded stage/kind
failure-code width required to store those categories without collapsing them
into `AGENT_PERSISTENCE_INVALID_ARGUMENT`. No upstream body is echoed.
Discovery invocation idempotency resolves exact replay before orchestration,
so a stored replay performs no paid/provider or Product work.

Gateway routing keeps Marketplace Discovery on its own circuit-breaker/time
limiter. Listing-bound Agent customer-service routes retain the established
15-second gateway window, while `/api/v1/agent/discovery/**` allows a
35-second transport window so the Gateway can outlive Agent's 30-second
whole-turn safety ceiling by a small deterministic margin. The browser does
not retry Discovery sends automatically; client disconnect and cancellation
remain authoritative. Stored Discovery USER history exposes the exact
server-persisted `clientMessageId` as a correlation-only field; ASSISTANT
messages expose `null`. The UI may use that field only to reconcile one
uncertain send after an explicit history refresh, never to render IDs or replay
a POST.

The deterministic offline comparison is synthetic, records zero external
provider/network calls, labels latency NON_PRODUCTION_SYNTHETIC, and always
keeps release BLOCKED. Runtime V2 alias/index evidence, secure flag activation,
one bounded provider-budget acceptance run and authenticated browser acceptance
remain deferred.
