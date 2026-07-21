# AI-DISC-01 Agent Conversational Marketplace Discovery

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

Demo canaries must use the explicit `demo-ai-discovery` frontend build when
the approved discovery chain is intentionally activated. The default Docker
build remains production, and the `demo-ai` build keeps discovery hidden so
listing customer-service can stay independently deployable.

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
Product request, eight seconds per provider request, and twelve seconds for a
whole turn. Each run permits at most five model calls, six total tool calls,
two searches, and five listing-detail calls. These are offline engineering
bounds, not production SLO evidence.

## Default-off composition

All new flags default false:

- `AGENT_DISCOVERY_API_ENABLED`
- `AGENT_DISCOVERY_KILL_SWITCH_ENABLED`
- `AGENT_DISCOVERY_ORCHESTRATION_ENABLED`
- `AGENT_DISCOVERY_PRODUCT_TOOLS_ENABLED`
- `AGENT_DISCOVERY_PROVIDER_ENABLED`

The API gate is evaluated before actor resolution, persistence, Product tools,
or model execution. Generation additionally requires the global kill switch
to be clear and every orchestration/tool/provider gate to be enabled.
Production release remains blocked; no gateway/frontend activation or live
provider configuration is part of AI-DISC-01A.

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
