# AI-DISC-01 Agent Conversational Marketplace Discovery

## AI-DISC-CS-P1-06 model-first controlled ReAct loop

The Marketplace assistant model is the primary planner. After durable message
acceptance and bounded context loading, each of at most five decisions returns
either natural customer-facing content or exactly one tool proposal. The
backend authenticates, validates schemas and actor scope, blocks cancellation
and duplicates, and enforces the one global decision budget; it does not use
the regex intent label to choose the action and does not force a first tool.

The runtime registry is the complete model-facing tool list:
`CHECK_AVAILABILITY`, `SEARCH_INDIVIDUAL`, and `GET_LISTING`. No
`RESPOND_DIRECTLY`, knowledge, account, order, or seller pseudo-tool is
advertised. Invalid proposals become bounded structured observations for the
next decision instead of being silently replaced. Cached inventory is supplied
as timestamped, freshness-labelled context; it never permanently determines a
later turn or prevents a changed query or explicit refresh.

Natural final content streams from that same Responses API decision. Only
`response.output_text.delta` reaches SSE; reasoning and planning events remain
private. The accumulated text is guarded, persisted exactly once, and must
equal the completed/history message. Product revalidation remains the only
source of recommendation attachments, which appear only after canonical
completion. The existing discovery-shaped public/history envelope remains
backward compatible for legacy rows and ordinary assistant messages, so this
refactor needs no new migration.

### Committed-turn terminal recovery

Once `MESSAGE_ACCEPTED` proves the USER row is durable, every generation exit
now atomically writes either the canonical assistant result or guarded
`HANDOFF` assistant copy with the stable failure category. Provider transport,
Product search, context loading, policy, and finalization failures therefore
remain visible after reload and offer the bounded response-only retry command.
The command reuses the invocation's original `clientMessageId` and USER row;
it never posts or inserts the customer message again.

History also recognizes an older `FAILED` invocation whose USER row exists but
whose assistant link is absent. It renders privacy-safe temporary-failure copy
and `Retry response` from invocation metadata rather than fabricating a search
result or replaying automatically. The retry limit and actor/session ownership
checks are unchanged. No persistence migration is required.

## AI-DISC-CS-P1-05 inventory-aware discovery tool policy

An explicit but under-specified product request no longer asks preferences before
checking whether that broad item is present. `I need a laptop` executes one
Product-owned `AVAILABILITY_PROBE` with only the normalized category and
`limit=1`; it performs no query embedding, hybrid ranking, listing verification,
or recommendation work. Product counts current active, approved,
quantity-positive individual inventory from its authoritative catalog. The
strict response distinguishes `CATEGORY_UNAVAILABLE`, `RESULTS_AVAILABLE`,
`FILTERS_TOO_STRICT`, `TEMPORARY_SEARCH_FAILURE`, and `SEARCH_UNAVAILABLE`; an
empty list alone never establishes inventory state.

When the probe proves inventory is zero, the assistant returns a normal
`NO_RESULTS` answer, stores `NO_INVENTORY`/`UNAVAILABLE`, asks no preference
question, and renders no cards. An unchanged same-category follow-up reuses
that state without another tool call; a changed category or explicit bounded
refresh permits a new probe. When inventory exists, the assistant may ask one
material use-and-budget question. A detailed request with a useful budget, use,
condition, or location proceeds directly to `FULL_DISCOVERY_SEARCH`: a
successful authoritative Product result itself proves availability, so a
separate probe is skipped. If that filtered search returns zero, the Agent runs
one broad Product probe to distinguish unavailable inventory from filters that
are too strict and exposes only bounded filter-category names.

The application remains the final tool-policy authority even when the model
proposes `SEARCH_INDIVIDUAL`. It rejects non-discovery, cancelled, unchanged,
already-unavailable, context-answerable, category-free, or under-constrained
search calls. Additive preference JSON stores `status`, `requestedCategory`,
`categoryAvailability`, `categoryInventoryCount`, `clarificationsAsked`, and
`lastSearchOutcome`; legacy history is still accepted and no migration is
required. Only a real probe emits `CHECKING_AVAILABILITY`; only a real full
search emits search/verification stages and can produce cards.

## AI-DISC-CS-P1-04 conversational customer-service orchestration

Marketplace Discovery is now one capability of the Marketplace customer-service
assistant rather than the universal turn type. Every new turn is routed through
one strict application-owned intent from `GENERAL_CONVERSATION`,
`MARKETPLACE_DISCOVERY`, `LISTING_QUESTION`, `CUSTOMER_SUPPORT`,
`SELLER_SUPPORT`, `CLARIFICATION`, `HANDOFF`, or `REFUSED` before any model,
query embedding, or Product work. Greetings, capabilities, general guidance,
seller image help, and buyer process questions resolve through bounded direct
responses with zero listing-tool calls. P1-06 supersedes the pre-model direct
response portion for all but its narrow deterministic gate. P1-05 supersedes P1-04 only for an
explicit identifiable but vague product request: it probes broad availability
before any useful clarification. Merely mentioning a product is not a search
request.

Detailed discovery retains the existing Product-owned hybrid retrieval and
per-listing authorization/revalidation contract. A listing-reference follow-up
binds only `GET_LISTING` against the latest validated recommendation context;
it cannot start an unrelated search. Contextual refinements retain the typed
session preferences and prior recommendation/exclusion state. Account, order,
payment, return, refund, dispute, delivery, safety, and seller questions never
fabricate private status: without an allowlisted integration they provide the
safe next step or capability boundary.

The persisted/API response adds the intent and the forward-compatible outcomes
`ANSWER`, `CLARIFY`, `SEARCH`, `ACTION_REQUIRED`, `HANDOFF`, and `REFUSED`.
Legacy `ASK_CLARIFY`, `RECOMMEND`, `COMPARE`, `DETAIL`, `NO_RESULTS`, and
`REFUSE` remain readable for stored discovery history; `RECOMMEND` is not used
as the assumed resolution for conversational/support turns. No schema migration
is required because the strict structured result is stored in existing message
metadata.

`MESSAGE_ACCEPTED` still proves durable USER persistence and drives the pending
bubble. `UNDERSTANDING`, `SEARCHING`, and `CHECKING` are emitted only by an
eligible orchestrator/tool boundary, and `COMPOSING` is shown only for discovery
or listing-detail work. Ordinary direct answers therefore show no search trail.
The client appends only stages actually received and never backfills a synthetic
search milestone. Recommendation cards remain gated to validated discovery
outcomes. Genuine final-answer SSE, Stop, PARTIAL persistence, response-only
retry, exact history reconciliation, default-off gates, and privacy rules are
unchanged.

### P1-03D5D authoritative Stop reconciliation

An explicit Stop can race the orchestrator's bounded graph-cancellation
translation. If that exact race has already stored the allowlisted
`DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_CANCEL` terminal state without an
assistant, the Stop command atomically attaches the existing guarded zero-text
`PARTIAL`/`HANDOFF` assistant and replaces the public failure code with
`DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED`. No other failed state is mutable.
Repeated Stop and response retry remain idempotent and reuse the one committed
USER row. After authoritative history confirms that USER by `clientMessageId`,
the browser removes its pending bubble immediately; reload renders the stopped
assistant and `Retry response`. `NOT_COMMITTED` still retains the draft and
creates no history.

### P1-03D5C opening-frame compatibility

The deployed D5 Agent image was stale relative to source and still prefixed
`MESSAGE_ACCEPTED` with a padded SSE comment block. The strict browser parser
correctly rejected that non-event block as `FRAME_SHAPE`. Agent images now
start directly with the versioned `activity` event. The browser parser also
implements the SSE standard narrowly by ignoring comment-only blocks; every
actual event still requires the exact two-line event/data shape, V2 schema,
monotonic sequence, allowlisted type, and strict payload. Unsupported SSE
fields remain rejected, and comments never become UI activity or text.

### P1-03D5 deployed-chain streaming stabilization

The authenticated message and response-retry SSE POSTs bypass Gateway MVC's
generic RestClient response copier and use a dedicated bounded servlet stream.
The handler reuses the BFF's validated OAuth client or bearer authentication,
forwards only authorization, correlation, JSON content type, and SSE accept
headers, and never forwards browser identity headers or cookies to Agent. It
copies upstream bytes unchanged on the bounded authenticated servlet request,
flushes each read, and retains the 35-second whole-stream deadline. This avoids
an asynchronous completion redispatch after D5A proved that such a redispatch
could truncate a committed SSE response and trigger an invalid JSON error tail.
Once SSE headers or bytes
are committed, timeout or disconnect closes the stream and cannot append a JSON
fallback. Discovery JSON routes remain on the existing wildcard route and
circuit breaker.

Gateway MVC's `streaming-buffer-size` remains 128 for other compatible streams,
but it is not the correctness mechanism for Discovery SSE: Spring Cloud Gateway
4.2 uses that property only for its upstream read array after exact media-type
selection; it does not configure the servlet response buffer. Production-chain
coverage now runs a real nginx proxy in front of the embedded Gateway and a
paused chunked upstream, observes `MESSAGE_ACCEPTED` before upstream release,
then validates the byte-exact monotonic V2 stream through `done` and asserts
that no asynchronous servlet redispatch occurs.

### P1-03D3 transport, cancellation, and presentation stabilization

The Agent sends exact `Content-Type: text/event-stream` without a charset
parameter. Spring Cloud Gateway MVC therefore selects its configured streaming
copy path and flushes each upstream read; nginx keeps buffering off only for
the Discovery stream route. No padding, timer, opening comment, or fake activity
is used. HTTP/socket coverage holds private orchestration blocked while the
consumer observes `MESSAGE_ACCEPTED` and `UNDERSTANDING`.

The composer clears and the pending USER bubble appears after
`MESSAGE_ACCEPTED`. Stop uses a dedicated actor/session/client-message command
instead of inferring persistence from whether that acknowledgement reached the
browser. A genuinely uncommitted request retains its draft; a committed request
always persists a
retryable `PARTIAL`/`HANDOFF` assistant, including the zero-text
`Response stopped before the answer began.` case. History and response-only
retry reuse the committed USER row and never post it again.

The D4 live failure showed that exact media type selection alone was
insufficient. The interim 128-byte Gateway MVC read buffer remains configured,
while Agent finalization reserves a three-second margin inside the unchanged
35-second ceiling. D5 moves the two SSE commands to the dedicated flushed relay
described above; neither mechanism adds artificial activity or answer pacing.

Canonical live and stored answers render as literal safe text with preserved
newlines; no Markdown or HTML is interpreted. USD recommendation cards use two
fraction digits from the authoritative decimal value. Listing follow-ups and
comparisons use the validated title instead of an ordinal so reorder/reload
does not silently retarget the request, and the UI labels compare intent as
`Comparing`.

### P1-03D completion and history stabilization

Guarded final answers and stored USER/ASSISTANT bodies may contain newline and
tab characters but reject every other ASCII control character. The Angular
stream and history validators use that same rule. A failed final validation or
atomic completion after any visible delta persists the exact visible text as a
retryable `PARTIAL` assistant with guarded `HANDOFF` result metadata; history
accepts this intentional resolution/outcome pair without weakening any other
role or outcome check.

Each durable, application-owned activity callback applies per-frame
backpressure until the Agent SSE generator has handed that exact frame to ASGI.
The producer therefore cannot enter the next private phase while accepted or
understanding activity is still only queued. The same delivery acknowledgement
applies to canonical text deltas; it is not a timer, heartbeat, invented stage,
or whole-answer buffer. Successful `done` collapses the live trail to a concise
summary derived only from milestones actually received; interrupted turns keep
their partial answer and do not show a successful summary.

## AI-DISC-UX-P1-03D genuine final-answer streaming correction

P1-03C's browser-paced replay is removed. After all private planning/tool calls,
Product authorization checks, and listing revalidation complete, Agent Service
starts one separately designated, tool-free OpenAI Responses stream constrained
to the validated public fact set. Only `response.output_text.delta` from that
call becomes a browser `text_delta`; reasoning, summaries, planning output,
prompts, tool arguments, vectors, scores, and provider errors are ignored. The
browser appends each network-arriving delta immediately to one assistant bubble
without timers, animation frames, substring replay, or artificial typing delay.

The accumulated text must complete, match the provider done event, pass the
public-text and existing Discovery response schema, and persist exactly once
before validated recommendation, provenance, and final identity events are
sent. Cancellation closes the provider HTTP stream and prevents late success
writes. Visible partial text is persisted as a retryable `PARTIAL` assistant;
response-only retry reuses the committed invocation and replaces only the prior
failed attempt, never its USER row.

While a turn is active, the assistant bubble presents the current fixed,
application-owned activity plus an expandable ordered trail. Stage changes are
announced politely; answer fragments are not announced. The UI never labels
this activity as model thought and never exposes provider reasoning, prompts,
vectors, tool arguments, identifiers, scores, or raw errors.

## AI-DISC-UX-P1-03B structured-response recovery stabilization

An unusable or malformed provider final structure never becomes a generic
successful `HANDOFF`. Application-owned evidence determines the outcome:

- a successful search proving zero candidates becomes the strict successful
  `NO_RESULTS` response and does not offer response retry;
- one or more freshly revalidated Product detail results become deterministic
  selections that still pass the existing hard-filter, query-relevance, and
  final recommendation guard before persistence;
- ambiguous evidence fails with the stable
  `DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA`
  code and a guarded assistant message that offers deliberate response retry.

Recovery logs only the fixed evidence/outcome category. Provider prose, raw
responses, prompts, tool arguments, listing identifiers, scores, and vectors
remain excluded. Migration V14 reclassifies only the former fixed generic
structure-failure assistant rows so an already committed message can use the
same response-only retry command without inserting another USER row. Forward
migration V15 removes only those rows' obsolete generic assistant and stale
tool/recommendation outputs so the retry can persist one new guarded result
without sequence collisions or duplicate cards.

## AI-DISC-UX-P1-03A streaming recovery stabilization

Discovery opens or resumes the actor's session and loads its first history page
before the UI can claim `Ready` or enable the composer. A truly empty resumed
session receives the greeting; an existing session renders its stored history
first. History-open and history-read failures remain distinct, read-only states
and never trigger an automatic send.

Query embedding has one application-owned retry only for the allowlisted
transient timeout. Each attempt is clipped to the remaining whole-turn
deadline, and the query embedding provider's internal retries are disabled so
the total cannot form a retry storm. Exhaustion keeps the stable 503/SSE failed
semantics and atomically attaches a deterministic `HANDOFF` assistant message
with no recommendations, citations, provider detail, vectors, scores, or raw
tool data. Migration V13 permits that guarded assistant link on a failed
invocation without changing retention behavior.

History exposes a bounded response-retry descriptor only for an owned, open,
failed invocation below the configured retry ceiling. New terminal generation
failures have guarded assistant rows; legacy FAILED invocations without that
row receive the same safe presentation from their invocation metadata.
The explicit `Retry response` action calls the response-only SSE command with
the committed USER message ID and preference version. Agent Service resolves
the original body and `clientMessageId` from persistence and uses the existing
atomic failed-invocation retry transition; it never accepts or inserts another
USER body. PENDING, stale-version, exhausted, and cross-actor failures fail
closed. No failure is automatically retried.

Specific product-and-budget requests search immediately; city or county is an
optional filter or later refinement. Product hybrid ranking and GET_LISTING
revalidation remain mandatory, and medical/safety refusal boundaries are
unchanged. Demo ZooKeeper and Kafka now have healthchecks and restart policies,
Kafka waits for ZooKeeper health, and Agent waits for Kafka health to prevent a
Docker Desktop wake-up restart loop without resetting broker data.

## AI-DISC-UX-P1-03 streaming discovery activity and validated answers

Marketplace Discovery now has an additive authenticated SSE message route at
`POST /api/v1/agent/discovery/sessions/{sessionId}/messages/stream`. The
existing synchronous JSON message route and response remain compatible. Both
routes use the same existing default-off Discovery API and generation gates,
actor isolation, `clientMessageId` idempotency, preference-version check,
orchestration, deterministic final guard, and persistence flow; no second
activation flag was added. The later recovery stabilization adds only forward
migration V13 for guarded terminal failure assistants.

The stream exposes only fixed application-owned progress stages:
`MESSAGE_ACCEPTED`, `UNDERSTANDING`, `SEARCHING`, `CHECKING`, and `COMPOSING`.
`MESSAGE_ACCEPTED` is emitted only after the durable invocation/user-message
begin succeeds. A completed idempotent replay emits no Product or provider
work. Private planning/tool-selection prose and deltas are never forwarded.
After the deterministic guard has produced validated public facts, one separate
tool-free final-answer Responses stream supplies the only user-visible deltas.
Its accumulated text is validated and persisted exactly once before completion.

Every SSE event uses
`MARKETPLACE_DISCOVERY_STREAM_EVENT_V2`, a strictly monotonic sequence, a
single-line JSON payload, and one of `activity`, `text_delta`,
`recommendations`, `metadata`, `done`, or `error`. Failures include only a
stable allowlisted code, safe message, and retry eligibility;
they never include request text, provider errors, prompt/tool data, vectors,
listing IDs, scores, or private reasoning. Cancellation retains the existing
failed-invocation cleanup and uncertain requests are never automatically
retried.

The browser uses credentialed `fetch` with the BFF CSRF header and never adds
an `Authorization` header. Its strict incremental UTF-8/SSE parser rejects
fragmentation errors, malformed/unknown fields, non-monotonic sequences,
disconnects without a terminal event, and a canonical delta/completion
mismatch. There is no JSON fallback after an uncertain stream. Exact
`clientMessageId` history reconciliation remains the only way to confirm an
uncertain accepted send.

The assistant bubble shows a compact vertical search trail and live answer with
a cursor. Stage changes, but not prose deltas, update the polite live region.
Recommendation cards and provenance render only after their validated events;
reduced-motion disables pulse/cursor animation. Stop generation aborts the
fetch and preserves partial text. The nginx frontend proxy disables buffering and caching only for
the Discovery path, uses HTTP/1.1, and keeps a 40-second read window above the
gateway's unchanged 35-second Discovery circuit-breaker timeout.

Source verification on 2026-07-28: focused Agent Discovery/API tests passed
(80), focused Gateway Discovery integration tests passed (8), focused Angular
Discovery component/service tests passed (40), the broader Angular Agent
feature suite passed (88), and application/spec TypeScript checks passed. No
runtime was deployed or recreated, no live provider call or
browser acceptance was performed, and no environment, application data, or Git
state was changed. Release defaults remain false and rollout evidence remains
deferred.

## AI-DISC-UX-P1-02 unified conversation-first marketplace assistant

The primary authenticated Agent experience is now one conversation-first
Discovery flow. When `aiDiscovery=true`, both `/account/messages/agent` and the
Marketplace agent row in the floating Messages panel open the same
`MARKETPLACE_DISCOVERY` timeline. They no longer expose a Discovery-versus-
listing-assistant switch, an `Already viewing an item?` detour, or a listing
picker before the user can write. The legacy `LISTING_CUSTOMER_SERVICE` UI is
retained only as a compatibility fallback when Discovery is disabled; it is
not a second mode inside the enabled Discovery experience.

The initial state is a normal assistant greeting, optional prompt chips, and
the persistent composer. It makes no Agent or Product request before explicit
submit. User and assistant turns remain in the existing Agent-owned MySQL
Discovery session. This preserves actor isolation, idempotency, optimistic
preference versions, history cursors, retention, New Search semantics, and the
rule that buyer/seller chat unread state is unrelated. `chat-service` remains
the owner of human buyer/seller conversations and does not become a second
authority for Agent messages.

`RECOMMEND` and `COMPARE` responses now expose only two additional verified
presentation facts already present in the freshly read Product projection:
the first safe public listing-media route and fixed seller type
`INDIVIDUAL`. The browser accepts only the exact internal public-media route
shape and renders listing cards directly from the typed response; it never
parses model Markdown into cards. Fulfillment is omitted because the approved
individual-listing public contract does not expose an authoritative
fulfillment field.

Each card offers `View listing`, `Ask about this`, and the existing explicit
exclusion action. `Ask about this` stays in the same composer and sends a
bounded ordinal reference to the latest server-owned recommendation snapshot.
The Agent may return `DETAIL` only with exactly one selection from that trusted
set after a fresh allowlisted `GET_LISTING` call. The deterministic guard
replaces model prose with current Product title, price/currency, condition, and
public area plus an explicit unknown-details notice. The selected listing ID is
stored in typed Discovery preference state for subsequent turns; a new Product
search or New Search clears it. `DETAIL` persists as the existing `ANSWERED`
resolution and does not replace the latest 3-5 recommendation snapshot, so
later ordinal comparison remains stable. No migration or public route was
added.

Current marketplace candidate retrieval continues through Product's approved
public individual search plus authoritative detail revalidation. Product may
use its rebuildable OpenSearch candidate projection, but this correction does
not claim or add semantic vector ranking for Discovery. Agent-owned listing
knowledge vectors remain a separate listing customer-service/RAG projection;
hybrid marketplace candidate retrieval requires a later Product/Search-owned
contract and cannot bypass Product visibility rules.

All capability defaults remain false and release remains `BLOCKED`. There was
no runtime activation, browser walkthrough, live provider request, external
OpenSearch operation, seller message, purchase, reserve, offer, or listing
mutation.

## AI-DISC-CORR-P1-01 question-first marketplace assistant correction

The observed listing-first local experience had two integration causes, not a
missing discovery architecture. The selected frontend build was `demo-ai`,
where `features.aiAssistant=true` and `features.aiDiscovery=false`; the
independent gateway Discovery flag and Agent Discovery generation flags were
also absent and therefore false. In addition, the floating Messages panel
shown in acceptance evidence was a separate entry path that embedded
`AgentCustomerServiceThreadComponent` directly whenever listing assistance was
enabled. It ignored the independent Discovery capability and therefore opened
the listing picker even though the routed `/account/messages/agent` page was
already query-first.

`docker-compose.demo.yml` now exposes the existing gateway and Agent Discovery
properties with false defaults. It does not enable them or change the frontend
build default. Local acceptance must explicitly select `demo-ai-discovery` and
must separately opt in the gateway and complete Agent Discovery chain; normal
production, development, and `demo-ai` behavior remains unchanged.

Both entry paths are now coherent. When Discovery is enabled, selecting the
Marketplace agent in the floating Messages panel opens
`AgentMarketplaceDiscoveryComponent`, focuses its question composer, shows no
listing collection, and makes no Discovery or Product request before explicit
submission. Listing-bound customer service remains available only through an
explicit `Ask about a listing` switch, with a corresponding return to
Discovery. When only listing assistance is enabled, the previous listing-only
behavior remains intact.

The bounded frontend contract gap is also closed. The strict browser model
and parser now accept the existing Agent `COMPARE` outcome, stored `ANSWERED`
resolution, and V8 top-level exclusion ledger. Comparisons render two to five
validated recommendation cards. A fixed-reason `Not interested` action calls
the existing exclusion endpoint with the current preference version, CSRF and
an idempotency key. Exact concurrent retries coalesce, conflicting reuse fails
closed, response session/listing identity is verified, uncertain writes are
never replayed, and version conflicts refresh authoritative state before an
explicit retry. No chat endpoint, Product API, persistence model, migration,
or Agent orchestration behavior changed.

Local source verification on 2026-07-23:

- focused Angular Discovery/page/service tests: 27 passed;
- focused floating Messages/Discovery boundary tests: 27 passed;
- full Angular suite: 483 passed;
- AI build-contract tests: 4 passed;
- production, `demo-ai`, and `demo-ai-discovery` builds: passed;
- focused Agent Discovery/API/evaluation/persistence/config tests: 76 run,
  67 passed and 9 configured container skips;
- demo activation configuration tests: 5 passed;
- gateway Discovery route tests: 5 passed and offline package: passed; and
- default-off/static checks confirm every newly exposed Compose property uses
  an explicit `false` fallback.

Two unrelated baseline failures remain outside this correction: the full Agent
suite has one customer-service test-helper signature error after 373 passes and
24 configured skips, and the full gateway suite has one listing-assistant
bearer-relay fixture returning 401 after 97 passes. Discovery-specific gateway
coverage is green. Docker was unavailable locally, so the explicit profile was
not started and authenticated browser acceptance remains blocked. No live
provider request occurred. Release stays `BLOCKED`, production evidence remains
unknown, normal defaults remain false, and this stabilization does not advance
the existing AI feature counter.

## AI-DISC-02B session-scoped explicit listing exclusion command

`AI-DISC-02B Session-Scoped Explicit Listing Exclusion Command` is implemented
locally behind the existing default-off Discovery API and generation gates. It
does not change or overload the discovery message endpoint. The additive
authenticated command is:

`POST /api/v1/agent/discovery/sessions/{sessionId}/exclusions`

It requires a printable-ASCII `Idempotency-Key` of 16-128 characters and a
strict body containing only `expectedPreferenceVersion`, one canonical
`listingId`, and an optional fixed `reasonCode`. Reasons are limited to
`NOT_RELEVANT`, `TOO_EXPENSIVE`, `TOO_FAR`, `WRONG_CONDITION`, `ALREADY_HAVE`,
and `OTHER`; free text and extra fields are rejected.

The target must belong to the authenticated actor and session's latest
successful `RECOMMEND` or `COMPARE` snapshot. A new exclusion increments the
preference version once and returns `EXCLUDED`. A new key for an already
excluded latest-set listing returns `ALREADY_EXCLUDED` without changing the
version or original reason. The session can contain at most 20 unique listing
IDs. Exclusions are append-only and never create user or assistant messages.
New Search creates a separate session with an empty exclusion ledger.

Session GET returns exclusions as a separate top-level array containing only
`listingId`, optional `reasonCode`, and `excludedAt`. Exclusions are not added
to the public preference object and contain no title, seller, store, prompt,
or inferred text. The application filters excluded IDs before Product detail
validation and deterministic ranking, so they cannot reappear in the same
session. Insufficient remaining candidates follow the existing clarification
or `NO_RESULTS` behavior and are never silently reintroduced.

Forward-only Agent migration `V8__create_discovery_exclusion_feedback.sql`
adds the actor/session exclusion ledger and a separate command table. Only a
SHA-256 idempotency-key hash and canonical request hash are stored. One
transaction performs actor/session hiding, exact replay before submitted
version or snapshot checks, latest-set eligibility, bounded append, version
advance, and stored response creation. Command tombstones expire 90 days from
creation. Exclusion content follows session-content retention; purge removes
the ledger and redacts any not-yet-expired command response content.

Stable conflicts use the existing discovery request and preference-version
codes plus `AGENT_DISCOVERY_EXCLUSION_NOT_AVAILABLE` and
`AGENT_DISCOVERY_EXCLUSION_LIMIT_REACHED`. Authentication/session hiding and
the standard safe correlation/error envelope are unchanged. Metrics contain
only fixed operation/result labels; no retry key, actor, session, listing, or
reason value is logged.

Verification is offline and fake-only. Focused tests cover the exact strict
schema, header and route boundary, actor/session hiding, separate GET
representation, replay and hash conflicts, version precedence, already-
excluded semantics, limits, New Search isolation, transaction/concurrency
coverage, ReAct pre-filtering, privacy and default-off zero-call behavior. V8
MySQL/Testcontainers cases are collected but remain opt-in when Docker is
stopped. No provider, Product write, OpenSearch, gateway, frontend, runtime,
or environment change is part of this slice. Release remains `BLOCKED`, all
flags remain false, and the AI feature counter advances from 1/3 to 2/3 only
after local verification is green.

## AI-DISC-02A detail-revalidated recommendation comparison

`AI-DISC-02A Detail-Revalidated Conversational Recommendation Comparison` is
implemented locally behind the existing default-off Discovery gates. It adds
no route, Product API, dependency, or migration. The existing discovery
message contract may now return a strict `COMPARE` outcome containing two to
five current public listing projections. Stored comparison turns use the
existing `ANSWERED` resolution and the existing V7 session/message retention
path.

Comparison is limited to listing IDs from the actor and session's latest
successful two-to-five item `RECOMMEND` or `COMPARE` snapshot. Ranks and public titles are
provided to the ephemeral LangChain turn only as untrusted reference data.
Every selected ID must pass the existing allowlisted `GET_LISTING` boundary
again. The deterministic final guard discards IDs outside the trusted set,
missing detail reads, removed listings, and listings that no longer satisfy
stored hard filters. Fewer than two eligible selections becomes `NO_RESULTS`;
otherwise the response states how many selected listings were omitted.

The comparison exposes only current authoritative title, category, condition,
price/currency, optional public city/region, short grounded facts, and
`listingId + checkedAt + responseHash` provenance. It does not infer missing
facts, compare different currencies as cheapest, or produce a winner claim.
Model-authored comparison claims and match reasons are not trusted. Medical,
privacy, prompt-injection, chain-of-thought, actor isolation, and no-action
rules remain unchanged.

Exact replay still resolves before the latest-snapshot read, Product detail
tool, or model. A new successful comparison leaves preference content
unchanged while advancing the existing optimistic preference version and
persisting atomically through the existing turn path. The model/tool/token and
time budgets remain bounded: seven model calls, six application tools, five detail reads,
8,000 input tokens, 800 output tokens, and the approved thirty-second
whole-turn safety ceiling.

Verification is fake-only and zero-cost. Focused tests cover strict schemas,
actor/session-scoped snapshot lookup, exact replay, preference/version
semantics, detail revalidation, removed and insufficient candidates, arbitrary
IDs, missing public location, mixed currencies, injection-resistant output,
deterministic offline parity, and the existing timeout/cancellation/tool-limit/
default-off boundaries. The MySQL integration suite includes compile-time
coverage for latest-set isolation and for retaining the previous `RECOMMEND`
set after a stored comparison; execution remains conditional on the existing
container runner.

All production, gateway, frontend, Product-tool, orchestration, and provider
flags remain false. The global kill switch remains engaged. The deferred
authenticated browser gate and all production quality, latency, cost, privacy,
policy, and rollout evidence remain blocked or unknown. Offline success cannot
authorize release. Full local verification is green, so this slice advances
the AI feature counter from 0/3 to 1/3 and stops before any successor.

## AI-DISC-CLEAN-P0-01 status

`AI-DISC-CLEAN-P0-01 Conversational Discovery Agent, Frontend, Gateway And
Evaluation Cleanup` is completed locally. It keeps every discovery capability
default-off and does not authorize deployment or rollout. The cleanup:

- reconstructs only the latest bounded prior messages and excludes the current
  user message from LangChain history;
- closes a newly created invocation as failed when orchestration is unavailable
  instead of leaving a pending idempotency record;
- retains one atomic persistence path for preference state, assistant results,
  invocation completion, and recommendation snapshots;
- aligns the documented detail-check budget with the enforced five-call tool
  limit;
- rejects concurrent frontend create/message requests that reuse one
  idempotency identity with different semantics;
- validates fixed constraint-coverage values and stored
  resolution/outcome/message consistency before rendering; and
- makes actor isolation an exercised fake Product denial in the offline
  evaluation rather than only an output inspection.

No gateway behavior or migration changed. Focused and full Agent, Angular, and
gateway verification is green; MySQL container tests remain opt-in and were
not started. The AI feature counter is reset from 3/3 to 0/3. Work pauses here;
offline PASS remains release `BLOCKED` and no successor starts automatically.

## AI-DISC-01A status

`AI-DISC-01A Agent Conversational Marketplace Discovery Session And ReAct
Orchestration` is completed locally and remains default-off. It does not
activate gateway or frontend routes and does not authorize rollout. The
bounded offline verification is green, so the AI feature counter is 1/3.

## AI-DISC-01B status

`AI-DISC-01B Query-First Marketplace Discovery Frontend And BFF Boundary` is
completed locally and remains default-off. The existing authenticated Agent
destination becomes query-first only when the independent frontend discovery
capability is enabled. Listing-bound customer service remains available as a
secondary mode and its deep links/picker are unchanged. Focused and full
frontend/gateway verification is green, so the AI feature counter is 2/3.

The browser client:

- makes no Agent, Product, or Auth request before explicit submit;
- creates/resumes one discovery session, loads strict stored history, and sends
  exact `clientMessageId + expectedPreferenceVersion + body` turns;
- never automatically replays an uncertain POST;
- refreshes session/history on conflict and requires explicit resubmission;
- renders only validated clarification questions, terminal states, and 3–5
  detail-checked listing cards with safe internal listing routes;
- exposes only `View listing`, never message, purchase, offer, contact, reserve,
  listing mutation, or trade actions;
- rejects extra fields, malformed IDs/timestamps, unsafe text, inconsistent
  shapes, invalid provenance, and arbitrary routes before rendering.

Frontend discovery uses the separate `features.aiDiscovery` capability, which
is false in production, development, and `demo-ai` builds. Gateway discovery
uses `GATEWAY_FEATURE_AGENT_DISCOVERY`, also false by default. The existing
`GATEWAY_FEATURE_AGENT` route now owns only listing customer-service sessions
and listing proposals; it cannot expose `/api/v1/agent/discovery/**`.

`AI-DISC-STAB-P1-01` adds the explicit Angular `demo-ai-discovery` build and
serve configuration. It is the only committed frontend configuration with
both `features.aiAssistant=true` and `features.aiDiscovery=true`; every other
feature retains its safe value. Docker still defaults
`FRONTEND_BUILD_CONFIGURATION` to `production` and can select this build only
through an explicit build argument. `AI-DISC-SEARCH-STAB-P1-42` additionally
guards the container build so the Discovery frontend configurations cannot be
selected for the approved local/demo container surface while
`GATEWAY_FEATURE_AGENT_DISCOVERY` is false. This source configuration does not
enable the Agent or gateway discovery flags, configure a provider, authorize
rollout, or change the release decision from `BLOCKED`.

`AI-DISC-STAB-P1-02 Production LangChain Discovery Orchestrator Composition`
closes the Agent startup composition gap exposed by the disabled canary. When,
and only when, the complete Discovery API/orchestration/Product-tool/provider
gate is valid and the global kill switch is clear, `create_app` now composes
the existing `MarketplaceDiscoveryOrchestrator` from the Agent-owned
`OpenAIProvider`, a strict request-scoped LangChain chat adapter, and the
approved public Product search/detail client. The adapter uses Responses with
`store=false`, strict allowlisted tools, the existing 800-output-token/six-tool
and configurable ten-second default model-call bound, and no LangChain memory
or persistence types. `AI-DISC-SEARCH-STAB-P1-46` makes that model-call bound
the provider request timeout as well as the adapter await timeout, so slow
provider transport fails as `provider_timeout` before the thirty-second graph
guard. Provider ownership is closed with the application
lifespan.

Normal default-off startup does not construct the Product client, provider,
or Discovery runtime and makes no call. Partial configuration remains
deferred/fail-closed; missing provider configuration fails startup before any
Product or provider request. Offline fake-transport tests prove the full
composition, request-scoped correlation, safe resource closure, and
coexistence with listing customer service. This stabilization does not change
the AI counter (`0/3`), activate any flag, add production evidence, or change
the release decision from `BLOCKED`.

## AI-DISC-01C status

`AI-DISC-01C Deterministic Offline Discovery Quality, Safety And Release-Gate
Evaluation` is completed locally. Its versioned runner executes the shipped
discovery orchestrator against deterministic fake LangChain model and Product
search/detail transports. It produces a strict machine-readable report,
self-excluding canonical SHA-256 digest, fixed low-cardinality observability,
and an explicit release gate.

The provisional offline baseline passes, but the release decision remains
`BLOCKED` and `releaseAuthorized=false`. All runtime switches remain off, the
global kill switch remains engaged, and production quality, latency, cost,
privacy, policy, and rollout evidence remain `UNKNOWN`. Offline PASS is not
production readiness. With focused and full Agent verification green, the AI
feature counter is 3/3; mandatory AI-DISC-only cleanup is next and no successor
starts automatically.

## Contract

- Only an authenticated actor may create or resume a
  `MARKETPLACE_DISCOVERY` session.
- An actor has at most one open discovery session. `newSearch=true` closes the
  previous session before creating a clean one.
- MySQL is authoritative for sessions, messages, typed preference state,
  idempotency, cursors, clarification counts, and recommendation snapshots.
  LangChain memory and checkpointers are not used.
- Discovery calls only the approved public individual-marketplace search and
  listing-detail Product APIs. It does not call Product databases, OpenSearch,
  arbitrary URLs, business listings, or write APIs.
- Location constraints are city/county only. Radius claims are forbidden.
- Every recommendation is detail-revalidated and carries
  `listingId + checkedAt + responseHash` provenance. No Product source version
  is invented.
- A recommendation contains 3–5 eligible listings after deterministic hard
  filters and bounded category diversity. Otherwise the result is a concise
  clarification, no-results response, refusal, or safe handoff.
- Clarification is bounded to three turns, two questions per turn, and five
  questions total. Medical diagnosis, treatment, cure, or outcome claims are
  refused; ordinary comfort-product discovery remains allowed.
- The Agent never purchases, messages, offers, publishes, or changes a
  listing. Chain-of-thought is never returned, persisted, logged, or audited.

## LangChain v1 boundary and budgets

The adapter uses the official Python v1 `langchain.agents.create_agent` API,
`ToolStrategy(DiscoveryTurnResult)`, a typed `AgentState`, and built-in
`ModelCallLimitMiddleware` / `ToolCallLimitMiddleware`. The exact direct
dependencies are `langchain==1.3.14` and `langchain-core==1.4.9`; LangGraph is
only a transitive runtime dependency and is not called through the deprecated
prebuilt ReAct helper.

The provisional local limits are 8,000 input tokens, 800 output tokens, 20
search candidates, five detail checks, five recommendations, two seconds per
Product request, a configurable ten-second default per discovery model call,
an eight-second query-embedding dependency cap, and twelve seconds for a
simulated offline p95 quality target. Runtime turns use a thirty-second
whole-turn safety ceiling, with provider/tool waits clipped to the remaining
turn budget and never allowed to extend that ceiling. Each run permits at most
seven model calls, six application tool calls,
two searches, and five listing-detail calls. The model-call ceiling is the six
allowed application tool rounds plus one final structured answer round.
LangChain represents the strict final `DiscoveryTurnResult` as a structured
output tool internally, but it is not counted against the six Product
application-tool budget. These are offline engineering bounds, not production
SLO evidence.

## Default-off composition

All new flags default false:

- `AGENT_DISCOVERY_API_ENABLED`
- `AGENT_DISCOVERY_KILL_SWITCH_ENABLED`
- `AGENT_DISCOVERY_ORCHESTRATION_ENABLED`
- `AGENT_DISCOVERY_PRODUCT_TOOLS_ENABLED`
- `AGENT_DISCOVERY_HYBRID_RETRIEVAL_ENABLED`
- `AGENT_DISCOVERY_QUERY_EMBEDDING_ENABLED`
- `AGENT_DISCOVERY_PROVIDER_ENABLED`

`AGENT_DISCOVERY_MODEL_CALL_TIMEOUT_SECONDS` defaults to `10.0` and is bounded
to `0.1..10.0`; it controls only each discovery chat model call. Query
embedding remains capped separately at eight seconds and Product calls at two
seconds so the thirty-second whole-turn ceiling remains authoritative.

The API gate is evaluated before actor resolution, persistence, Product tools,
or model execution. Generation additionally requires the global kill switch
to be clear and every orchestration/tool/provider gate to be enabled.
Production release remains blocked; no gateway/frontend activation or live
provider configuration is part of AI-DISC-01A.

### AI-DISC-03A hybrid listing retrieval

The conversation-first search tool now has an independently gated hybrid
candidate path. Product remains authoritative and supplies BM25-ranked public
individual-listing IDs through
`GET /api/v1/internal/agent/marketplace/listings/search`, protected by the
existing constant-time Agent service token. Product reloads OpenSearch IDs
from MySQL before returning them. The response contains only `listingId` and
`lexicalRank`.

Agent semantic retrieval reuses the existing versioned
`msb-agent-knowledge-read` OpenSearch alias and its LISTING `knn_vector`
chunks; no second vector database or new embedding store is introduced. Agent
performs deterministic reciprocal-rank fusion with constant 60, then reloads
every fused candidate through Product's approved public detail endpoint.
Removed, unavailable, non-individual, zero-quantity, or hard-filter-mismatched
listings are discarded before LangChain can recommend them. The model never
supplies trusted actor or candidate IDs.

`AGENT_DISCOVERY_HYBRID_RETRIEVAL_ENABLED=false` is the committed default.
Enabling it requires the existing Product tool gate, Product service token,
knowledge index, embedding identity, orchestration and provider gates. The
kill switch and API gate still precede persistence, Product, OpenSearch, and
provider work. This source slice does not constitute production readiness or
authorize a live embedding/provider request.

Conversation history reads remain a separate persistence operation. A history
failure is reported as history unavailable and explicitly says listing search
was not attempted; a message-time 503 is reported as listing search
unavailable while preserving the already-loaded history and typed text. Stored
USER history messages include the opaque server-persisted `clientMessageId`
only for correlation after an explicit refresh; ASSISTANT messages return
`null`. The frontend must not render, log, or use that value to resend a
message. It clears an uncertain draft only when refreshed history contains the
exact pending `clientMessageId`.

## Persistence

Forward-only Agent migration
`V7__create_marketplace_discovery_sessions.sql` adds subjectless discovery
state and typed recommendation snapshots. Existing listing customer-service
subject invariants remain unchanged. Recommendation content follows the
existing 90-day Agent message retention and is deleted with session content;
bounded audit metadata retains its existing 365-day maximum.

## Verification boundary

Tests use fake Product and fake model transports only. They cover strict
schemas, actor isolation, replay/hash conflict, preference concurrency,
cursors, clarification and medical boundaries, Product tool/call limits,
detail revalidation, stale/removed rejection, citations/provenance,
timeout/cancellation, malformed structured output, chain-of-thought
rejection, and default-off zero-call behavior. No provider request, token
usage, cost, external OpenSearch, runtime activation, or rollout evidence is
produced.

### AI-DISC-01C offline evaluation contract

The fixture
`agent-service/evals/ai_disc_01c_offline_baseline_v1.json` covers broad
sleep-comfort intent, clarification followed by refinement, budget/city/county/
condition constraints, missing public facts, stale/removed listings,
prompt/listing injection, privacy and chain-of-thought boundaries, medical
claims, no results, fake Product outage, timeout, cancellation, malformed
structured output, call-limit enforcement, and replay/ranking determinism.
Every recommendation case executes search and detail revalidation through
fakes; no real Product, provider, or OpenSearch request occurs.

Exact provisional thresholds are `1.0000` for strict schema conformance,
allowlisted tool boundaries, actor/listing isolation, privacy redaction,
injection resistance, medical boundaries, citation and provenance validity,
hard-filter compliance, stale-source rejection, fake-failure coverage,
recommendation count compliance, ranking stability, and replay idempotency.
Grounded reasons must be at least `0.9500`; clarification quality must be at
least `0.9000`. Simulated p95 must be at most 12 seconds and is always labeled
`NON_PRODUCTION_SIMULATED`.

The report requires zero external provider requests, zero provider tokens,
zero cost, `UNPRICED` cost status, unique fixture cases, complete failure
coverage, fixed metric names, deterministic canonical JSON, and a verified
self-excluding digest. Evidence is valid for seven days only; future-dated,
stale, tampered, missing, duplicate, or schema-incompatible evidence is not
release evidence. The CLI is available as `msb-discovery-eval` or
`python -m msb_agent_service.marketplace_discovery_evaluation`.

### Product-owned discovery embedding transition

`AI-DISC-SEARCH-P0-04B` implements a separate default-off Agent job that
consumes Product's reference-only `listing.discovery.embedding-requested`
version-1 event, reads the exact request-bound Product source, uses the existing
replaceable embedding provider, and submits an ephemeral vector through a typed
future Product callback client. The job never writes source text or vectors to
Agent persistence or the Agent knowledge index.

This transition does not extend the older direct Agent-side hybrid experiment
described above. Product 04C vector receipt, Product vector mapping/backfill,
Product-owned hybrid ranking, and later `SEARCH_INDIVIDUAL` integration remain
deferred. The detailed source contract is
`docs/mvp/ai/ai-disc-search-p0-04b-agent-discovery-embedding-worker.md`.

### Product-owned hybrid query integration

AI-DISC-SEARCH-P0-07 replaces the earlier Agent-side semantic/OpenSearch
experiment with one default-off query embedding followed by Product's strict
P0-06 hybrid API. Product remains responsible for BM25, vector retrieval,
deterministic RRF, filters and MySQL revalidation. Agent preserves Product rank
and versioned retrieval provenance in existing tool-call source references,
then uses SEARCH_INDIVIDUAL and GET_LISTING for current display facts. The
public live/history schema is unchanged. See
docs/mvp/ai/ai-disc-search-p0-07-agent-hybrid-integration.md.

### Parallel Marketplace Agent V2 results-first continuity

`AI-DISC-AGENT-V2-RESULTS-FIRST-CONTINUITY-03B` keeps the parallel V2
orchestrator model-first while bounding inventory work to one Product search
per turn. Once that structured observation exists, the next provider call is a
terminal answer call with no tools, preventing altered duplicate searches and
five-step rejection loops. The strict backend policy also rejects a second
search if a non-conforming provider attempts one.

Selection follow-ups resolve only against the latest ordered Product-validated
recommendations. `the second one` reuses the second card; `show me a cheaper
one` selects the lowest current price and attaches that validated listing to
the new assistant response. If provider comparison prose is unsupported, the
guard composes a bounded answer from public title, price, condition, and
location facts without a new search. Listing guidance uses the listing page and
buyer-seller trade coordination terminology, never a platform purchase or
order claim. The existing generic message, attachment, SSE, Stop, Retry, and
history contracts are unchanged; no migration is required.
