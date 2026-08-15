# AI-DISC-AGENT-V2-PLAN-01 parallel Marketplace Agent V2 proposal

Status: **implemented behind a default-off feature flag**. The legacy discovery
endpoint remains registered and unchanged; no runtime cutover is implied.

## Problem boundary

The current `/api/v1/agent/discovery/**` implementation is operational and must
remain available. Its orchestration is coupled to `MARKETPLACE_DISCOVERY`
sessions, `DISCOVERY_RESULT` message actions, discovery outcomes, recommendation
rows, and discovery-specific SSE/UI DTOs. Agent V2 will not refactor
`marketplace_discovery.py`; it will run beside it and reuse only stable service
adapters and persistence infrastructure.

## Proposed package boundary

```text
msb_agent_service/marketplace_agent_v2/
  api.py             separate authenticated V2 routes
  service.py         idempotent turn lifecycle and cancellation
  orchestrator.py    one five-decision controlled loop
  schemas.py         strict decisions, observations, messages, attachments
  tools.py           authoritative three-tool registry and safe observations
  policy.py          authorization, schema, ownership, duplicate, budget checks
  persistence.py     V2 session/history decoder over shared infrastructure
  provider.py        stored-disabled Responses streaming adapter
  runtime.py         default-off production composition
  api.py             strict V2 errors and SSE serialization
```

The package may depend on the existing provider and Product adapters. The
legacy discovery package must not depend on V2.

## API and activation proposal

- Default-false `AGENT_MARKETPLACE_V2_API_ENABLED` and an independent kill
  switch guard all V2 session and generation routes.
- Proposed route root: `/api/v1/agent/marketplace-v2`.
- Session, history, synchronous message, and streaming message commands remain
  separate from legacy discovery routes. `AI-DISC-AGENT-V2-CUTOVER-READINESS-01`
  adds response-scoped Stop and Retry commands without redirecting production.
- Enabling V2 does not redirect or reinterpret a legacy request. A later
  explicitly approved frontend cutover chooses the V2 endpoint.

## Decision and tool contracts

One model decision is exactly one of:

```json
{"type":"assistant_content","content":"customer-facing text"}
```

```json
{
  "type":"tool_proposal",
  "callId":"provider-scoped opaque value",
  "tool":"check_availability | search_listings | get_listing | request_confirmation | collect_listing_information",
  "arguments":{}
}
```

`MAX_AGENT_STEPS` is exactly 5 and one proposal is allowed per decision. The
backend validates the registered tool, strict arguments, actor/session scope,
known-listing ownership, cancellation state, duplicate normalized call, and
remaining budget. A rejection becomes a bounded timestamped observation for
the next model decision; the backend never substitutes another tool.

Initial registry:

- `search_listings`: wraps the existing query-embedding plus Product-owned
  hybrid retrieval adapter and current Product detail revalidation. It returns
  explicit success/no-result/temporary-failure reasons, validated listing
  attachments, and, for the opt-in V2 Product response, a bounded total and
  Product-derived subtype/condition/price/location facets.
- `check_availability`: wraps Product's authoritative count-only broad-category
  probe and never returns recommendations.
- `get_listing`: accepts only a listing already present in V2 session context
  and wraps the existing public Product detail/revalidation adapter.
- `collect_listing_information`: starts a persisted `CREATE_LISTING`
  information-collection workflow and one `ANSWER_FIELD` interaction. It is a
  no-I/O state control and cannot create, publish, or mutate a listing.

RAG, orders, accounts, seller draft/publication integrations, and handoff are
not registered or advertised in this slice.

Each safe observation contains `tool`, `status`, `reason`, `observedAt`, an
optional `expiresAt`, bounded filter categories, and validated attachment
references. It excludes prompts, reasoning, vectors, raw scores, service
credentials, and private Product payloads.

## Seller workflow context (`AI-DISC-AGENT-V2-SELLER-CONTEXT-03`)

`activeWorkflow` and `pendingInteraction` are additive session-state JSON.
`CREATE_LISTING` collects one field at a time without claiming that a seller
draft or publication integration exists. A `WAITING` `ANSWER_FIELD` is resolved
atomically before broad scope classification or model planning; the committed
USER message ID is stored as the last resolved identity so response Retry does
not consume the next field. Refresh and service restart restore the same state.

While that workflow is collecting information, inventory tools are rejected
with `TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW` unless the customer explicitly asks
for comparable listings or market pricing. Such a comparison is a side trip:
the current seller field remains pending and collected fields are retained.
Cancellation clears the pending field. No migration is required because the
state uses the existing `preference_state_json` document.

### Pending-field semantics (`AI-DISC-AGENT-V2-PENDING-FIELD-04`)

Seller fields are stored as `{status,value,reason}` rather than ambiguous
nullable strings. `PROVIDED` contains a validated value. `REJECTED` retains the
refused value with an allowlisted reason; `MISSING`, `DEFERRED`, and
`NEEDS_HELP` contain `null`. Existing string-valued workflow state remains
readable as `PROVIDED`.

Before general scope or ReAct planning, the backend resolves a waiting field
reply as `VALUE_PROVIDED`, `UNKNOWN`, `DEFER`, `REQUEST_HELP`,
`CANCEL_WORKFLOW`, `CORRECTION`, `REPLACE_FIELD_VALUE`, or
`UNRELATED_OR_NEW_INTENT`. Obvious English
and Chinese control phrases are handled deterministically. Unknown and help
replies keep the same field pending, defer advances without storing control
text, cancellation closes the workflow, and unrelated turns leave the pending
field intact for context-aware model handling. The committed USER identity and
resolution are stored so response Retry cannot consume the field twice.

The approved Marketplace surface is for goods and Product's active taxonomy
does not include real estate. An unambiguous house item type therefore rejects
only `ITEM_TYPE`, retains `CREATE_LISTING`, and opens one replacement interaction
without inventory search or an unsupported policy claim. Broader prohibited-item
guidance still requires a real approved policy capability.

### Workflow continuation (`AI-DISC-AGENT-V2-WORKFLOW-CONTINUITY-05`)

After the model selects `collect_listing_information`, the tool may carry an
explicit initiating `itemType`; a bounded backend extractor also preserves an
item named directly in the initiating seller sentence so it is not asked twice.
Unsupported values use `REJECTED/UNSUPPORTED_CATEGORY` while the workflow stays
`COLLECTING_INFORMATION`. The pending `ITEM_TYPE` interaction sets
`acceptsReplacement`, allowing phrases such as “how about a phone” and their
approved Chinese equivalents to replace the value atomically before general
scope or ReAct planning. A supported replacement advances directly to `TITLE`;
another unsupported replacement keeps the same goal and a new pending identity.
Legacy terminal `UNSUPPORTED` JSON is recovered under the persistence row lock
on its next reply. No Product/RAG call, database migration, or new SSE event is
introduced; the replacement marker remains private session/model context and
does not widen the public pending-interaction DTO.

## Message and streaming contracts

The persisted assistant contract is generic:

```json
{
  "role":"ASSISTANT",
  "content":"Plain customer-facing text",
  "attachments":[],
  "citations":[],
  "toolActivity":[]
}
```

Listing cards are optional validated attachments. Direct answers use the same
message contract with empty arrays.

The V2 SSE schema is strict, versioned, and monotonic. Its initial event set is
`message_started`, `activity`, `tool_completed`, `text_delta`, `attachments`,
`error`, and `done`. Only terminal customer-facing model text is streamed.
Tool selection, prompts, internal observations, and reasoning remain private.

## Product APIs reused unchanged

- `POST /api/v1/internal/agent/marketplace/listings/hybrid-search` through
  `HybridMarketplaceDiscoveryClient.search_individual()` for Product-owned
  retrieval/ranking after one ephemeral query embedding.
- `GET /api/v1/public/listings/{listingId}` through
  `ProductMarketplaceDiscoveryClient.get_listing()` for current public detail
  validation and response hashing.

The internal lexical `/search` and `/availability` endpoints are not V2 tools.
No Agent database access to Product data is introduced.

## Persistence and migration proposal

A forward-only `V17` migration implements the boundary because using the existing
`MARKETPLACE_DISCOVERY` session type would make one endpoint reject the other
endpoint's history.

The smallest reuse strategy is:

- add `MARKETPLACE_AGENT_V2` to the existing `agent_sessions` type/subject
  constraints;
- add a separate generated open-session marker and actor uniqueness key for
  V2, leaving the legacy open-session key unchanged;
- store compact V2 state in the existing `preference_state_json` column but
  decode it only with V2 schemas;
- reuse `agent_invocations`, `agent_messages`, and `agent_tool_calls`, with
  validated listing facts persisted as generic attachment snapshots;
- persist generic content in `body`, citations in `sources_json`, and bounded
  tool activity in `actions_json`; V2 history does not require a
  `DISCOVERY_RESULT` action.

V17 allows the lowercase `search_listings` and `get_listing` audit names.
Forward-only V18 adds `check_availability` after cutover-readiness evidence
showed that Product's authoritative count probe was required before broad
discovery. Existing migrations are not edited.

## Cutover-readiness boundary

`AI-DISC-AGENT-V2-CUTOVER-READINESS-01` keeps V2 default-off and adds a
development-only authenticated browser surface. V17 must be applied to the
real local Agent schema before enabling that surface. The development build may
activate all V2 gates explicitly; production and ordinary development builds
remain disabled.

Stop cancels the owned in-flight task and reconciles against durable state. A
committed USER turn receives a retryable `PARTIAL` assistant even when no model
text was produced. Response Retry identifies the committed USER row and its
original `clientMessageId`; it never posts a second USER message. History also
marks a failed final USER row with no assistant link as response-retryable.

Full-search empty results are explicit: Product-owned broad inventory zero is
`CATEGORY_UNAVAILABLE`; broad inventory with no exact validated matches is
`FILTERS_TOO_STRICT`; transport/tool failure is `SEARCH_UNAVAILABLE`. None is
inferred from an empty list alone.

Safe Product observations are restored from the existing bounded actions JSON
so terse follow-ups can be interpreted against prior result shape and facets.
Only an identical normalized call within one invocation is blocked; changed
queries and filters remain model-driven and policy-validated. The exact cancellation
phrase `never mind` terminates with zero model decisions and zero tools, while
ordinary direct answers still use exactly one model decision.

## Results-first discovery (`AI-DISC-AGENT-V2-RESULTS-FIRST-03`)

A broad identifiable product noun may go directly to `search_listings`; the
count-only probe is optional rather than a forced first step. Agent requests
`MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3`, while Product continues to return the
unchanged V1 and V2 shapes to older callers. V3 adds `discovery.normalizedCategory`, bounded
`totalMatches`, `relevantMatchCount`, evidence-derived
`retrievalConfidence=HIGH|MEDIUM|LOW`, an explicit reason, and Product-derived
subtype, condition, price-band, and public-location facets over current
MySQL-revalidated relevant candidates. Product removes known accessory results
for a main-product query and normalizes supported synonymous chair subtypes.

Configurable Agent thresholds (`DIRECT_RESULT_MAX`,
`CLARIFICATION_RESULT_MIN`, and `MAX_CLARIFICATION_OPTIONS`) classify the safe
observation as `NO_RESULTS`, `DIRECT_RESULTS`, `RESULTS_WITH_REFINEMENT`, or
`LOW_CONFIDENCE`. High/medium-confidence candidates are Product-revalidated and
the top-K cards are retained even for diverse broad inventory. A bounded
optional refinement is generated only from Product-owned facets and is shown
after those cards; selecting it fills the composer and never auto-sends. Low
confidence hides candidate cards and permits one interpretation question.
Terminal model text continues to stream directly and is not buffered into a
clarification replay. The observation and optional refinement use existing
bounded actions JSON, so no migration is required; legacy observations remain
readable.

## Customer result presentation (`AI-DISC-AGENT-V2-RESULT-PRESENTATION-04`)

The V3 Product projection additively reports bounded `exactMatchCount` and
`relatedMatchCount`. Product sorts full multi-concept matches ahead of partial
matches and retains fused rank as the tie-breaker. The Agent terminal model
receives only this customer-safe result summary, not current card facts,
listing IDs, Product facets, or ranking-confidence metadata. Validated cards
retain IDs privately for links and ordinal follow-ups.

Search prose is one short introduction and does not enumerate facts already in
cards. A bounded SSE redactor removes known listing IDs and prohibited internal
phrases before persistence or browser delivery. Optional refinements ignore
broad taxonomy subtypes and use only revalidated match scope, useful price,
condition, and public-location facts. The application renders at most one
question after cards; selecting a refinement fills an explicit command and
never auto-sends. Existing subtype refinements remain readable in history. No
migration or legacy-route cutover is introduced.

`AI-DISC-AGENT-V2-RESULT-PRESENTATION-04A` closes the results-first ordering
regression where terminal model prose could ask several refinement questions
before attached cards. On a result-bearing turn, the streamed model introduction
is declarative only. Question sentences are withheld at the Agent boundary, and
only Product-derived structured actions render after the validated cards. If the
model supplies only a question, the existing grounded same-turn result summary
is used; no extra provider call, migration, attachment change, or SSE event is
introduced.

`AI-DISC-AGENT-V2-RESULTS-FIRST-CONTINUITY-03A` closes the short-facet
continuation gap. When a terse reply exactly matches a subtype from the latest
successful Product search observation—or exactly matches the authoritative
public category on its revalidated attachments when subtype facets are absent—
the Agent supplies the model with one
strict `contextualRefinement` containing that Product-owned value, the active
query, and the required search query. Product subtype selections may refine the
query text. A public attachment category such as `General` does not become a
free-text search term: the Agent keeps the active product query unchanged,
requires the model's strict `categoryName` tool filter, and retains only
Product-revalidated listings whose authoritative public category matches.
Customer prose cannot terminate
that turn before the refined search executes; it becomes a structured policy
rejection for the next bounded decision instead. Invalid provisional prose is
not streamed. The model still proposes the tool, the policy requires the exact
contextual query, and Product remains authoritative for the updated results.
No public event/response field or migration is added.

## Product-owned multi-concept ranking (`AI-DISC-AGENT-V2-MULTICONCEPT-RERANK-05`)

Agent V2 requests the opt-in Product V4 hybrid response. Product expands a
bounded normalized concept vocabulary, runs strict synonym-aware lexical and
semantic candidate retrieval, revalidates the candidate set from Product
MySQL, rejects candidates missing a core concept or matching an incompatible
broad product type, and deterministically orders `HIGH` matches before bounded
`MEDIUM` alternatives. Numeric BM25, vector, fusion, and compatibility scores
never leave Product. Product returns only strict `EXACT|RELATED` evidence; the
Agent rechecks each selected listing through the existing public-detail tool
and explains the structured result. There is no second Agent model-reranking
call.

The browser groups validated cards as closest matches and related alternatives.
Legacy attachments without match-quality metadata remain readable. The
checked-in ten-query fixture reports Precision@3, Precision@5, Recall@10, MRR,
nDCG@5, and displayed missing-core percentage without provider or network
calls. Product metadata does not yet include normalized product type, brand,
model, or compatibility attributes. A later Product-owned asynchronous
publication/enrichment step may add versioned normalized attributes to the
search projection; retrieval must continue to work from authoritative listing
facts while that projection is absent or stale. This slice adds no migration,
does not rebuild the index, and leaves V1-V3 callers unchanged.

## Contextual response ownership (`AI-DISC-AGENT-V2-CONTEXTUAL-RESPONSE-01`)

The model is the sole owner of terminal customer prose. Product-derived
refinements contain only bounded action labels and counts; Angular renders the
buttons without a second backend-written question. The latest assistant
message with validated listing sources defines the ordered active
recommendation set. Comparison, best-value, cheapest, and ordinal follow-ups
use this set, may revalidate one known listing when a current fact is missing,
and cannot run a broad search unless the customer changes requirements,
requests fresh results, or rejects the current set.

The backend validates terminal comparison claims against active public facts
before persistence. It rejects missing ordinals, non-member winners,
unsupported exact-match counts or best claims, duplicate follow-up prose,
results-without-evidence claims, and newly introduced listing identities.

For a materially changed refined search only, the model may propose the strict
no-I/O `request_confirmation` control action. The resulting pending interaction
is stored in existing V2 session JSON and in the originating assistant action
snapshot. A committed `yes` or `no` atomically changes `WAITING` to `CONSUMED`
or `CANCELLED`; only the first accepted confirmation can run its stored search.
Ordinary result display and comparison never require confirmation. No database
migration or legacy endpoint change is required.

## Action-evidence boundary (`AI-DISC-AGENT-V2-ACTION-EVIDENCE-02`)

Only a persisted `WAITING` refined-search interaction may solicit a yes/no
answer. Listing display, comparison, and detail guidance remain direct and do
not create confirmation state. A rejected or failed tool observation cannot be
followed by prose claiming that the action completed.

The initial V2 registry has no gallery-opening, extra-photo, seller-contact,
private pickup/payment-instruction, or purchase-start tool. The model therefore
directs the customer to the existing validated listing card/page for those
needs. Terminal validation rejects unavailable action offers and execution
claims, while the bounded stream guard prevents known positive execution claims
from reaching SSE before the existing retryable failure is persisted. This is
an additive orchestration rule with no schema migration or frontend contract
change.

## Listing thumbnail presentation (`AI-DISC-AGENT-V2-LISTING-THUMBNAILS-03`)

Every revalidated V2 listing attachment retains Product's first ordered,
approved public-media URL as nullable `thumbnailUrl`. The V2 browser card uses
that URL through the existing Gateway media route, lazy-loads it, and keeps a
stable non-image placeholder when a listing has no approved media or the media
request fails. The browser parser accepts only the versioned
`/api/v1/public/listing-media/{imageId}` path; external or arbitrary image URLs
fail closed. Images are presentation-only and do not alter ranking, ordinal
identity, persistence, Retry, or exact-once history. Existing attachment JSON
already includes the nullable field, so no migration or Product/Agent runtime
change is required.

## Terminal coverage (`AI-DISC-AGENT-V2-TERMINAL-COVERAGE-04`)

Every committed V2 USER turn ends with either the canonical successful
assistant message or the existing retryable guarded assistant failure. The SSE
`error` event is emitted only after that failure write, and the development
browser immediately reloads authoritative history instead of leaving its
temporary assistant bubble or requiring the user to resend. A normal provider
greeting may contain the common two-question conversational shape; result and
action turns retain the one-follow-up validation boundary.

When all five model decisions are consumed after a successful Product search,
the terminal guard completes the turn from the current validated attachment set
and allowlisted applied-filter categories. It does not request another turn,
repeat card facts, call the provider again, or make claims beyond Product-owned
observations. This is additive to the V2 message/action JSON and requires no
migration.

## Guarded response recovery (`AI-DISC-AGENT-V2-GUARDED-RECOVERY-05`)

Explicit attempts to obtain hidden instructions, internal reasoning, or
private seller contact details terminate as a canonical refusal before any
model or tool call. Requests to cure, treat, or diagnose a medical condition
receive a narrow non-medical marketplace boundary and clinician guidance;
they do not recommend supplements, medication, or health outcomes. These are
successful policy responses, not retryable service failures.

For comparison turns, untrusted model text remains withheld until the terminal
guard accepts it. If the model returns an unsupported comparison shape, V2 may
recover only from the active, Product-validated public recommendation facts:
title, price, condition, and public location. It never introduces a listing or
runs a broad search. Obvious keyboard-noise input that still produces an
unsupported model response receives one short no-tool clarification. Other
validation failures keep the existing guarded retry contract but are no longer
described as marketplace-service unavailability. No persistence migration or
frontend event change is required.

## Ordinal-safe comparisons (`AI-DISC-AGENT-V2-ORDINAL-PRESENTATION-06`)

Comparison answers preserve the ordered identity of the active recommendation
cards. If model prose reverses the first and second validated titles or exposes
serialized condition/relevance fields, the terminal guard replaces it with a
bounded comparison composed from the ordered public facts. Conditions use
customer labels such as `Like New`; internal match classifications are omitted.

When validated cards are attached in the current turn, the answer must not ask
permission to view, see, show, or display those same results. The stream
sanitizer removes the narrow known redundant question shape, while the provider
contract permits at most one useful refinement, detail, or comparison question.
No persistence, attachment, SSE event, Product, or frontend schema changes are
required.

## Unified messages cutover (`AI-DISC-AGENT-V2-MESSAGES-CUTOVER-07`)

Builds with the existing `marketplaceAgentV2` frontend flag now render the V2
conversation surface inside the Marketplace assistant row on
`/account/messages`. Buyer/seller conversations and their Chat Service routes
remain unchanged. When the flag is false, the legacy assistant component
remains the fallback; this keeps the cutover reversible by frontend image or
build configuration.

The embedded V2 surface uses customer-facing Marketplace assistant labels and
hides development evaluation evidence. The separate authenticated
`/account/marketplace-agent-v2` route remains available in the opted-in build
for bounded evaluation. Reserved legacy assistant links redirect to the unified
messages page after cutover so they cannot reopen legacy orchestration. No API,
persistence, Product, or SSE schema changes are introduced.

## Floating assistant cutover (`AI-DISC-AGENT-V2-FLOATING-CUTOVER-08`)

Builds with the same existing `marketplaceAgentV2` frontend flag now use the
V2 conversation surface in the authenticated floating Marketplace assistant
as well as `/account/messages`. The compact surface resumes V2 history and
keeps direct responses, validated listing attachments, Stop, and response-only
Retry on the same V2 contracts. It hides evaluation evidence and constrains the
conversation to the floating panel's scrollable space.

Buyer/seller chat in the floating panel remains owned by Chat Service. The
legacy Discovery component remains the flag-off fallback, so rollback requires
only the prior frontend image or configuration. No Agent, Gateway, Product,
persistence, SSE, or migration contract changes are introduced.

## Marketplace scope gate (`AI-DISC-AGENT-V2-SCOPE-GATE-01`)

V2 classifies only the broad marketplace boundary after the USER row and
recent context are loaded. The internal result is one of `IN_SCOPE`,
`CONVERSATIONAL`, `OUT_OF_SCOPE`, `UNSAFE`, or `AMBIGUOUS`, with bounded
confidence, context-use, and reason categories. It is never included in the
customer wire response. Uncertain short product or contextual language remains
with the model-first planner; the scope gate never chooses search, listing
detail, clarification, comparison, or another tool.

Clear unrelated requests complete with one concise marketplace-boundary
assistant response and no provider, embedding, Product, activity, attachment,
or refinement work. Conversational messages may receive a natural model answer
but every marketplace tool proposal is rejected by policy. Persisted
out-of-scope message pairs are excluded from later planner context, while the
active recommendation set and pending marketplace state remain available for
the next valid turn. Safe low-cardinality scope metadata is stored only as an
assistant action for audit and regression correlation. The slice is additive
to V17/V18 and requires no migration or public API field.

### Strict scope and grounding (`AI-DISC-AGENT-V2-SCOPE-GROUNDING-02`)

The private scope result also records `requiredGrounding` as `NONE`,
`LISTING_DATA`, `KNOWLEDGE_RAG`, or `PRIVATE_TOOL`. High-confidence unrelated
general-knowledge, programming, weather, creative-writing, homework, and
prompt-injected variants complete at the scope boundary with zero provider or
tool work. An out-of-scope turn is excluded from later planner context and does
not mutate active recommendations, preferences, or pending interactions.

For grounded turns, terminal prose is not exposed until the backend proves the
required evidence exists. Listing claims require a current Product observation
or a validated active recommendation. Policy claims require an approved
knowledge-document observation, and actor-specific status claims require an
authorized private-tool observation. A missing requirement becomes a bounded
`GROUNDING_REQUIRED` observation within the same five-decision budget; final
exhaustion is a safe abstention, never model-memory fallback. Internal evidence
references are stored as private assistant actions and never rendered or sent
in SSE.

The current executable V2 registry has Product listing tools only. The existing
knowledge projection contains `LISTING` and category-scoped
`CATEGORY_GUIDANCE`; it does not provide a general refunds/orders/policy corpus
or an actor-private order/account adapter. V2 therefore abstains for those
questions until a real allowlisted runtime tool and approved corpus are added.
It does not advertise or simulate either capability. No migration or public
wire field is introduced.

## Local combined main publication

The localhost Marketplace runtime uses `docker-compose.demo-ai-main.yml` as an
override of `docker-compose.demo.yml`. The override selects dedicated
`main-ai` image tags for Agent, Gateway, and frontend, builds the frontend with
`demo-agent-v2` so the dedicated V2 route and unified assistant surfaces are
present, and keeps both Cart and Agent Discovery Gateway flags enabled. The
overlay also pins the approved non-secret Agent activation
categories: persistence, customer-service compatibility, and Marketplace V2
are enabled; legacy Discovery, the V2 kill switch, hybrid retrieval, and query
embedding remain disabled. This prevents an unrelated local business-only build of a mutable
`latest` image from silently becoming the running port-4200 Marketplace site.

The overlay does not change production defaults or include credentials. Local
publication continues to use the existing Compose environment opaquely and
must use the base file and override together. Rollback selects the preceding
immutable image tags; Product, Auth, persistence, and infrastructure services
are not part of this publication boundary.

## Approval gates for implementation

Implementation was approved before source changes. Required gates
include the nine-case fixture in
`agent-service/evals/marketplace_agent_v2_evaluation_v1.json`, strict policy and
five-step tests, provider-stream/cancellation tests, persistence exact-once and
history tests, legacy endpoint regression tests, frontend generic-message and
optional-attachment tests, API contract tests, and production builds.
