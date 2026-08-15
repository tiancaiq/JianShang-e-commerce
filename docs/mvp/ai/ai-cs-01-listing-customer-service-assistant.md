# AI-CS-01 Listing Customer-Service Assistant

Status: planning complete and hybrid-RAG contract reconciled by `AI-RAG-00`.
The `AI-CS-01A` persistence foundation is implemented, verified, migrated
locally, and deployed with its runtime gate disabled. `AI-CS-01B` authenticated
APIs are implemented and verified with their capability gate disabled.
`AI-CS-01C` listing-only hybrid-RAG orchestration source behavior is implemented
and verified with mocked/offline model execution. `AI-CS-01C-2` provider
answerer adapter binding is implemented and verified with fake transports only.
`AI-CS-01D-A` disabled-by-default gateway/UI source integration is implemented
and verified. The mandatory `AI-CS-CLEAN-P0-01` cleanup checkpoint is complete
and `AI-CS-01E-A` now supplies a verified deterministic offline evaluation
baseline plus provisional threshold/report contracts. `AI-CS-01E-B` now adds
the machine-enforced release-gate evaluator and offline-observability contract.
`AI-CS-01E-C` adds the default-off public-listing context picker and explicit
listing-detail launch. The mandatory `AI-CS-CLEAN-P0-02` evaluation,
release-gate, and listing-context cleanup is complete, and the AI lane is reset
to 0/3. `AI-CS-02B` adds the Python LangChain Core v1 adapter and
default-off production composition behind the existing Agent-owned interfaces.
`AI-CS-02C` adds only the explicit, tracked `demo-ai` build and default-off
Compose/runbook contract. Subsequent bounded session/CSRF stabilization and
privacy-safe create-session diagnostics are reconciled by
`AI-CS-CLEAN-P0-03`, resetting the AI lane to 0/3. No runtime was activated.
Live evaluation, dashboards, cohorts, and rollout remain unimplemented.

Release: V3.

## AI-CS-02B Python LangChain Core Runtime Boundary

The approved runtime dependency is exactly `langchain-core==1.4.9`. The full
`langchain` package and LangGraph are not required because this slice uses only
typed `RunnableLambda` and `RunnableSequence` composition. The adapter:

- remains behind the existing `QuestionAnswerer`;
- delegates authorization, Product eligibility, filtered retrieval, provider
  calls, grounding, citation validation, redaction, budgets, cancellation,
  errors, metrics, and tool audits to the existing application-owned code;
- adds no LangChain type to external DTOs, persistence, BFF contracts, or
  OpenAPI;
- uses no LangChain memory, checkpointer, tool discovery, agent executor, or
  tracing transport;
- preserves MySQL as the only session, message, invocation, and replay
  authority.

Production composition binds the existing OpenSearch listing retriever,
OpenAI embedding/provider adapters, listing-only orchestrator, and LangChain
adapter only when all of these Agent-owned gates pass:

- `AGENT_CUSTOMER_SERVICE_API_ENABLED=true`;
- `AGENT_CUSTOMER_SERVICE_KILL_SWITCH_ENABLED=false`;
- `AGENT_CUSTOMER_SERVICE_ORCHESTRATION_ENABLED=true`;
- `AGENT_CUSTOMER_SERVICE_RETRIEVAL_ENABLED=true`;
- `AGENT_CUSTOMER_SERVICE_PROVIDER_ENABLED=true`.

All gates remain false by default. Create and send operations fail before
actor resolution, Product reads, invocation persistence, retrieval, or
provider execution when generation is disabled or kill-switched. Existing
owned session and history reads remain available through the authenticated API
boundary when the API itself is enabled.

The current Product projection still limits trusted current facts to listing
identity/version, title, thumbnail, seller type, eligibility, and transaction
notice. The runtime may ground answers in approved listing passages, title,
and transaction notice only. It must return uncertainty or seller handoff for
price, exact or coarse location, quantity, condition, negotiability, or other
facts that the approved projection does not supply.

Official API basis:

- Python LangChain v1
  [Runnable composition](https://python.langchain.com/api_reference/core/runnables/langchain_core.runnables.base.RunnableSequence.html)
  and asynchronous `ainvoke`;
- `langchain-core`
  [typed `with_types` boundaries](https://python.langchain.com/api_reference/core/runnables/langchain_core.runnables.base.Runnable.html);
- no LangGraph state machine because the application-owned orchestration is
  already deterministic and bounded.

No migration, gateway, frontend, Product, Auth, runtime flag, provider key, or
external OpenSearch change is part of AI-CS-02B. Offline evaluation parity
must remain exact, zero-provider, zero-token, zero-cost, and release
`BLOCKED`.

## Goal

When conversational Discovery is enabled, the primary Marketplace agent no
longer exposes this historical listing-picker flow as a separate user mode.
Discovery owns the continuous need-to-results conversation and can detail-
revalidate one selected recommendation in place. The listing-bound customer-
service implementation remains a disabled compatibility foundation until a
later approved contract reuses its broader knowledge-answering capability
behind the unified Discovery session without creating a second conversation.

Define the first listing-bound AI customer-service experience by activating
the existing `Marketplace agent` entry in the marketplace chat UI.

The assistant answers questions about one active, approved individual listing
using current application data plus filtered, source-attributed public
knowledge. It is a platform AI assistant, not the seller, and it does not
participate in the buyer/seller conversation.

## Dependencies

- `AI-00` agent and automated-operations plan.
- `AI-RAG-00` hybrid-RAG source, retrieval, citation, retention, and privacy
  contract.
- `AI-RAG-01` through `AI-RAG-03` for the future vector foundation, ingestion,
  and filtered retriever.
- `AI-01` tool-grounded listing reads.
- `LST-11` public approved listing detail.
- `CHAT-04` floating marketplace chat launcher.
- Existing authenticated BFF session and correlation handling.

## Approved Planning Decisions

- Use an isolated Python/FastAPI agent service with the OpenAI Agents SDK.
- Start with authenticated marketplace users only.
- Start with one agent and two read-only tools: `getListing` and
  `retrieveKnowledge`.
- Reuse the existing chat UI presentation and its `Marketplace agent` entry.
- Keep agent sessions and messages outside `chat-service` persistence.
- Keep report classification in shadow mode until a later approved slice.

## Scope

In scope:

- listing-bound agent session creation;
- text questions and text answers;
- current listing tool reads and its approved transaction notice;
- filtered public knowledge from eligible listing content, marketplace policy,
  safety guidance, FAQs, and category guidance;
- source-attributed answers and deterministically validated UI actions;
- activation of the existing agent row in the floating chat launcher;
- activation of the existing agent row in `/account/messages`;
- deterministic listing selection when the agent opens without listing
  context;
- existing thread, message, composer, loading, retry, and responsive visual
  patterns;
- session, message, tool-call, usage, and result-status audit metadata;
- focused agent evals and frontend/backend authorization tests.

Out of scope:

- guest agent sessions;
- agent messages inserted into buyer/seller conversations;
- sending a message as the seller;
- negotiation, offers, deal acceptance, or trade actions;
- listing edits, submission, approval, publication, pause, close, or removal;
- seller image-to-listing generation;
- reports or automated operations;
- business listing customer service;
- realtime delivery, WebSocket, SSE, voice, attachments, web search, file
  search, MCP, shell, sandbox, specialist agents, or agent handoffs.

## Existing Chat UI Reuse

AI-CS-01 reuses two existing Angular surfaces:

- `frontend/src/app/features/chat/floating-chat.component.ts`;
- `frontend/src/app/features/account/conversation-shell.component.ts`.

Both surfaces already render a `Marketplace agent` row and a coming-soon
placeholder. The implementation replaces that placeholder with the working
agent thread.

Reusable presentation behavior includes:

- the agent row and active state;
- split conversation/thread layout;
- thread header and listing context;
- user and assistant message bubbles;
- composer and character counter;
- loading, empty, error, and retry states;
- mobile responsive behavior;
- focus management and accessible live-message announcements.

The implementation may extract small presentational components when needed to
keep the floating and full-page surfaces behaviorally aligned. It must not
move buyer/seller authorization, completion behavior, or chat domain state
into generic UI components.

### Data boundary

UI reuse does not mean domain or API reuse:

- `ChatService` continues to call only buyer/seller chat APIs.
- A separate Angular `AgentService` calls only `/api/v1/agent/...` APIs.
- Agent DTOs remain separate from `ConversationSummary`, `ChatMessage`, and
  `ConversationCompletion`.
- A small presentation view model may adapt both message types for shared
  rendering.
- Agent sessions are not returned by `GET /api/v1/conversations`.
- Agent messages do not affect buyer/seller unread state.
- Trade-completion controls and off-platform deal actions never render in an
  agent thread.

## Entry Flows

### Listing detail

1. An authenticated user opens an active, approved individual listing.
2. The user selects the marketplace agent entry from the existing floating
   chat UI or a listing-context AI action that opens the same agent thread.
3. The frontend creates or resumes an agent session bound to that listing.
4. The thread header shows the listing title, thumbnail, and an `AI assistant`
   label.
5. The user sends a question through the existing composer pattern.
6. The assistant answers using current listing facts and filtered approved
   knowledge.
7. A question requiring seller knowledge displays a `Message seller` action
   that uses the existing listing conversation flow.

### Global chat launcher or account messages

1. The authenticated user selects the existing `Marketplace agent` row.
2. If there is no active listing-bound agent session, the thread panel shows a
   deterministic listing picker instead of calling the model.
3. The user selects an active individual listing from marketplace search or
   enters a supported listing link/ID resolved through the application API.
4. The frontend creates or resumes the listing-bound session.
5. The normal agent thread and composer replace the listing picker.

The model does not choose an arbitrary listing based on private history. A
session has exactly one subject listing. Changing listings creates or resumes
the matching listing-bound session.

## Product Rules

- The interface always labels assistant messages as AI-generated.
- The assistant distinguishes listing facts from the approved transaction
  notice, retrieved knowledge, and versioned runtime safety instructions.
- Price, quantity, negotiability, condition, location, and availability come
  from current tool results, not prompt memory.
- Factual answers identify the supporting source type, ID, and version.
- Product Service structured facts override conflicting vector content.
- Effective marketplace policy and safety guidance override FAQs and category
  guidance. Same-precedence conflicts produce uncertainty.
- Missing or conflicting information produces an uncertainty response.
- Listing and policy text are untrusted model input, not instructions.
- The assistant cannot claim that the platform verifies or protects
  off-platform payment.
- The assistant cannot reveal seller or buyer email, phone, exact address,
  Keycloak subject, role assignments, storage internals, or moderation data.
- The assistant cannot create, send, or modify a buyer/seller message.
- The assistant cannot negotiate, accept a deal, reserve a listing, mark a
  transaction done, or select a buyer.
- A listing eligibility check occurs at session creation and before every
  answer that relies on listing data.
- When the listing is no longer active and approved, the session becomes
  read-only and the UI links back to marketplace discovery.
- OpenAI or tool failure does not affect listing detail, search, or seller
  messaging.
- The frontend renders only validated route-semantic actions and never parses
  free-form answer text to infer behavior.

## Service Ownership

### Agent service

The isolated agent service owns:

- `LISTING_CUSTOMER_SERVICE` sessions;
- user and assistant agent messages;
- prompt, tool, model, schema, and policy versions;
- safe tool-call metadata and result status;
- token usage, latency, cost, and trace correlation;
- agent-specific rate limits and evaluation fixtures;
- deterministic knowledge chunking and embedding;
- versioned OpenSearch knowledge indexes, aliases, retrieval, invalidation,
  deletion, and lag metrics.

The agent service stores the app-owned actor user ID and subject listing ID. It
does not store Keycloak tokens, storage credentials, private contact details,
or copies of unrestricted listing media.

### Product service

Product service remains authoritative for:

- listing existence, type, visibility, and version;
- public listing fields and transaction notice;
- seller-safe public identity data already approved for listing reads;
- listing availability for agent sessions;
- approved listing descriptions and public attributes supplied to the
  knowledge indexer;
- versioned category buying guidance.

The agent service has no product-service database credentials.

### Moderation and support ownership

The moderation/support module remains authoritative for versioned:

- marketplace policy;
- payment, delivery, and meeting-safety guidance;
- public marketplace FAQs.

Source owners expose versioned reads or durable change/invalidation events.
The agent service never reads their databases.

### Chat service

Chat service remains authoritative only for buyer/seller conversation
mechanics and completion behavior. AI-CS-01 adds no chat-service table,
conversation type, participant, message, or migration.

## API Contract Direction

All routes use `/api/v1` through the existing authenticated BFF boundary.

### Create or resume a listing session

```text
POST /api/v1/agent/sessions
```

Request:

```json
{
  "sessionType": "LISTING_CUSTOMER_SERVICE",
  "subject": {
    "type": "LISTING",
    "id": "01L00000000000000000000001"
  }
}
```

Rules:

- The current actor is derived from the authenticated BFF session.
- Client-supplied actor, seller, business, role, or permission fields are not
  accepted.
- The listing must be active, approved, and `INDIVIDUAL`.
- The operation returns the existing open session for the actor/listing pair
  or creates one atomically.
- The uniqueness rule is one open `LISTING_CUSTOMER_SERVICE` session per
  `(actorUserId, subjectListingId)`.

Response:

```json
{
  "id": "01A00000000000000000000001",
  "sessionType": "LISTING_CUSTOMER_SERVICE",
  "status": "OPEN",
  "subjectListing": {
    "id": "01L00000000000000000000001",
    "version": "12",
    "title": "Used bicycle",
    "thumbnailUrl": "/api/v1/public/listing-media/01I...",
    "transactionNotice": "Payment and delivery are arranged directly by participants."
  },
  "createdAt": "2026-07-14T12:00:00Z",
  "updatedAt": "2026-07-14T12:00:00Z"
}
```

### Send a question

```text
POST /api/v1/agent/sessions/{sessionId}/messages
```

Request:

```json
{
  "clientMessageId": "01C00000000000000000000001",
  "body": "Is the price negotiable?"
}
```

Rules:

- Only the session owner may send or read.
- `clientMessageId` deduplicates client retries for the same session owner.
- Blank and over-limit messages are rejected before a model call.
- The request is synchronous in the first implementation.
- The service first stores one user message and a `PENDING` invocation keyed by
  `clientMessageId`, calls OpenAI outside the database transaction, then stores
  one assistant message and marks the invocation `SUCCEEDED` in a final
  transaction. Failure marks the invocation `FAILED` without creating an
  assistant message.
- Timeout or dependency failure returns the standard error envelope and does
  not create duplicate user or assistant messages on retry. A retry returns a
  prior successful result or performs one bounded retry of a failed invocation.

Response:

```json
{
  "userMessage": {
    "id": "01M00000000000000000000001",
    "role": "USER",
    "body": "Is the price negotiable?",
    "createdAt": "2026-07-14T12:01:00Z"
  },
  "assistantMessage": {
    "id": "01M00000000000000000000002",
    "role": "ASSISTANT",
    "body": "Yes. The seller marked this listing as negotiable.",
    "resolutionType": "ANSWERED",
    "sources": [
      {
        "sourceType": "LISTING",
        "sourceId": "01L00000000000000000000001",
        "sourceVersion": "12",
        "label": "Current listing"
      }
    ],
    "actions": [],
    "createdAt": "2026-07-14T12:01:01Z"
  }
}
```

### Read a session and messages

```text
GET /api/v1/agent/sessions/{sessionId}
GET /api/v1/agent/sessions/{sessionId}/messages?cursor=&limit=
```

Messages use cursor pagination ordered by `(createdAt, id)`. Cross-user access
returns the approved hidden-resource response and never reveals whether a
session exists.

## Initial Agent And Tools

Start with one `Listing customer-service assistant` agent.

Initial function tools:

| Tool | Purpose | Side effect |
|---|---|---|
| `getListing` | Read current eligible public listing facts | None |
| `retrieveKnowledge` | Read bounded, filtered, approved public passages | None |

Both tools receive trusted runtime actor/session context rather than
client-supplied actor identity. `getListing` accepts no model-selected actor or
listing ID. `retrieveKnowledge` accepts only a bounded query plus optional
allowlisted source types and language; its handler injects the actor, subject
listing, public visibility, effective policy time, and retrieval limits.

The retriever supports `LISTING`, `MARKETPLACE_POLICY`, `SAFETY_GUIDANCE`,
`MARKETPLACE_FAQ`, and `CATEGORY_GUIDANCE`. Listing-specific chunks must match
the session subject and current listing version.

The agent has no general HTTP tool, direct database tool, unrestricted
OpenSearch query, S3, browser, web-search, file-search, shell, sandbox, or MCP
access. `getMarketplacePolicy` is not a separate tool; approved versioned
policy is retrieved through `retrieveKnowledge`.

### Answer contract

Assistant messages include:

- `resolutionType`: `ANSWERED`, `PARTIAL`, `UNKNOWN`, `CONTACT_SELLER`, or
  `REFUSED`;
- zero or more validated sources with type, ID, version, and safe label;
- zero or more validated actions from `MESSAGE_SELLER`, `VIEW_LISTING`, or
  `BROWSE_MARKETPLACE`.

Every factual citation must refer to a tool result from the current invocation.
Actions target only the subject listing and represent application routes, not
arbitrary URLs or commands. Invalid citations, actions, or structured output
fail closed.

## Frontend Direction

### Floating marketplace chat

- Keep the existing bottom-right launcher and `Marketplace agent` row.
- Replace `Coming soon` with an availability state when the feature flag is
  enabled.
- Selecting the row opens the agent thread without calling `ChatService`.
- Listing detail can open the same launcher with the current listing context.
- The panel preserves recent buyer/seller chats and does not merge their
  records with agent sessions.

### Account messages

- Keep the existing `Marketplace agent` row in `/account/messages`.
- Add explicit agent routes before the existing conversation parameter route:

```text
/account/messages/agent
/account/messages/agent/:sessionId
```

- Agent selection must not pass an agent session ID to `ChatService` as a
  conversation ID.
- Browser refresh restores the selected agent session when authorized.

### Thread behavior

- Header: AI label plus listing title, thumbnail, and listing link.
- Body: existing message-list pattern with distinct user/assistant labels.
- Answer body: render safe source labels and validated actions without parsing
  the answer text.
- Composer: text only, stable height, character counter, send/loading state.
- Failure: retry the failed question without duplicating the user message.
- Unavailable listing: disable composer and show a marketplace discovery link.
- Seller question: show `Message seller`; do not send anything automatically.
- Agent thread never renders trade-completion cards or controls.

## Persistence Direction

Agent Service Flyway V4 now includes:

- `agent_sessions`;
- `agent_messages`;
- `agent_invocations`;
- `agent_tool_calls`.

Recommended constraints and indexes:

- unique active session key for actor, session type, and subject listing;
- unique `(session_id, actor_user_id, client_message_id)` for retry
  deduplication;
- message pagination index `(session_id, created_at, id)`;
- actor session index `(actor_user_id, updated_at, id)`;
- invocation lookup by session, correlation ID, result status, and created
  time.

The agent service uses a separate MySQL schema and forward-only Flyway SQL
migrations stored with `agent-service`. A dedicated migration job applies them;
application startup does not create or update schema. The original planning
slice added no migration; `AI-CS-01A` subsequently added
`V4__create_agent_customer_service_persistence.sql`.

Open sessions and message bodies are retained for 90 days after last activity.
Safe invocation and tool-call version, status, usage, latency, cost, and
correlation metadata is retained for 365 days. Production message bodies and
retrieved passages are excluded from unrestricted logs, traces, analytics, and
evaluation fixtures.

The OpenSearch knowledge projection stores only approved public chunks and
strict source metadata. Listing and source invalidation uses durable,
idempotent events. The initial update/deletion propagation target is 15
minutes; excessive lag disables affected vector-backed answers. Current
`getListing` eligibility and version checks prevent stale vector content from
overriding application state.

## Observability

Record structured logs and metrics without unrestricted message bodies or
private listing data:

- session create/resume result;
- listing eligibility rejection;
- question validation rejection;
- tool call name, status, and latency;
- model request status, latency, token usage, and estimated cost;
- guardrail rejection;
- deduplicated retry;
- unavailable listing transition;
- retrieval result count, source types, filter status, and index lag without
  passage bodies;
- OpenAI and application dependency failures.

Logs and traces include correlation ID, agent session ID, actor app user ID,
subject listing ID, prompt/tool/schema/model versions, and result status. Raw
prompts, message bodies, tool payloads, storage URLs, email, phone, and exact
location are excluded from unrestricted logs and traces.

Create-session request-validation diagnostics are a narrower pre-handler
exception: they retain only correlation ID, bounded error count, fixed
location/error categories, allowlisted top-level/subject key presence, and the
runtime type and length (never the value) of `subject.id`. Their metric labels
are fixed and contain no arbitrary field name, request value, actor, listing,
session, header, cookie, token, prompt, or upstream response. The public
`400 VALIDATION_ERROR` envelope remains unchanged.

## Test And Evaluation Plan

`AI-CS-01E-A` establishes the offline baseline contract:

- `evals/ai_cs_01e_offline_baseline_v1.json` is a strict, seeded, versioned
  fixture covering current retrieval, stale/deleted/cross-listing rejection,
  actor/tool isolation, grounding/citations, prompt injection, deterministic
  handoff, and fake index/provider failure modes;
- the runner emits
  `ai-cs-offline-evaluation-report-v1` without question, passage, or answer
  bodies and can emit its JSON Schema for automated validation;
- provisional thresholds are retrieval relevance/recall `>= 0.90`, all
  grounding/safety/boundary/failure rates `== 1.00`, and simulated offline p95
  latency `<= 500 ms`;
- annotated claim support is the offline faithfulness definition. It is not a
  semantic-model score, and simulated latency is not a production SLO;
- provider requests, tokens, and estimated cost remain deterministically zero;
  pricing is unapproved;
- a passing offline baseline cannot open release. All five release switches
  remain false, and live-quality, production-latency, pricing, and rollout
  blockers remain present.

`AI-CS-01E-B` machine-enforces the release boundary:

- the current raw report must pass strict schema, internal consistency,
  SHA-256 evidence integrity, expected version, and seven-day freshness
  checks;
- all offline thresholds and required tool/model/OpenSearch fake-failure
  coverage must pass;
- all five explicit capabilities must be enabled and every kill switch must be
  clear; default-off and kill-switch states always force `BLOCKED`;
- production-quality, production-latency, and production-cost gates remain
  `UNKNOWN` without bounded current external evidence;
- internal, small-cohort, and wider rollout approvals are sequential;
- offline dashboard metrics use only fixed metric names plus `OFFLINE`,
  `LISTING`, and bounded dependency labels;
- simulated latency and zero-cost metadata never satisfy production
  latency/cost gates.

Agent-service unit tests:

- creates or resumes one session per actor/listing;
- validates message length and client message ID;
- deduplicates retried questions;
- invokes only the expected read tools;
- closes or locks a session when listing eligibility is lost;
- records safe invocation and tool metadata;
- handles model timeout without duplicate answers.

Integration and authorization tests:

- authenticated user can open a session for an eligible listing;
- guest receives `401`;
- cross-user session read/send is hidden or forbidden consistently;
- inactive, unapproved, removed, business, or missing listing is rejected;
- product-service failure returns a temporary dependency error;
- vector retrieval enforces subject-listing, public visibility, language,
  effective-date, version, and invalidation filters;
- OpenSearch failure safely degrades only when current listing facts fully
  support the answer;
- agent service has no direct product or chat database access.

Frontend tests:

- both existing agent rows remain visible for authenticated users;
- selecting the agent calls `AgentService`, not `ChatService`;
- listing-context launch creates or resumes the matching session;
- global launch shows the deterministic listing picker;
- messages use the existing thread and composer presentation;
- loading, retry, unavailable-listing, and empty states render correctly;
- no trade-completion controls render for an agent session;
- seller escalation uses the existing buyer/seller conversation path;
- direct agent route refresh restores an authorized session;
- mobile layout contains the thread, composer, and longest expected labels
  without overlap.

Agent eval cases:

- answers price, negotiability, quantity, condition, and approximate location
  from current tool data;
- cites correct listing, policy, safety, FAQ, or category source versions;
- rejects cross-listing, stale, invalidated, expired, and deleted passages;
- follows source precedence when structured facts conflict with vector content;
- says information is unknown when the listing does not contain it;
- refuses to negotiate, accept a deal, or verify payment;
- preserves the off-platform payment disclosure;
- ignores prompt injection in listing title, description, attributes, and
  policy text;
- does not reveal private contact, exact location, storage, identity, or
  moderation fields;
- handles conflicting listing fields safely;
- handles same-precedence knowledge conflicts with uncertainty;
- uses no tool when the message is unrelated or disallowed;
- degrades safely when OpenAI or a tool is unavailable.

End-to-end smoke flow:

1. Sign in to the marketplace.
2. Open an active approved individual listing.
3. Open the existing floating chat launcher and select `Marketplace agent`.
4. Ask a factual listing question.
5. Verify the answer is grounded in the current listing.
6. Open `/account/messages/agent/{sessionId}` and verify session continuity.
7. Ask a seller-specific question and use `Message seller`.
8. Disable the agent feature or OpenAI dependency and verify normal listing
   detail and buyer/seller chat remain available.

## Feature Flags And Rollout

Required flags:

- agent service availability;
- vector knowledge availability;
- listing customer-service capability;
- floating-chat agent entry activation;
- account-messages agent entry activation.

Rollout order:

1. local and CI evals;
2. internal authenticated users;
3. small authenticated-user cohort;
4. wider authenticated rollout after grounding, safety, error, latency, and
   cost gates pass.

The feature flag restores the existing coming-soon placeholder or hides the
active composer without affecting buyer/seller chat.

## Acceptance Criteria

- The existing marketplace chat UI is the agent's user interface.
- Both existing agent placeholders have a defined activation path.
- Agent UI presentation is reusable without merging agent and chat domain
  state.
- Sessions are authenticated, listing-bound, actor-isolated, and read-only
  toward marketplace data.
- Answers use current structured listing facts plus filtered, source-attributed
  public knowledge.
- Source ownership, precedence, visibility, staleness, deletion, retention,
  citations, and action allowlists follow `AI-RAG-00`.
- The assistant cannot impersonate the seller or execute marketplace actions.
- Unknown facts, unavailable listings, dependency failures, and disallowed
  requests degrade safely.
- API, persistence, authorization, frontend, observability, and eval direction
  are documented.
- The original planning slice changed no application code, migration,
  dependency, or environment configuration; the scoped `AI-CS-01A`
  implementation is recorded below.

## AI-CS-01A Implementation Report

Completed on 2026-07-19:

- added Agent Service Flyway V4 with `agent_sessions`, `agent_messages`,
  `agent_invocations`, and `agent_tool_calls`;
- enforced one open listing-customer-service session per actor/listing with a
  nullable generated open marker and unique key;
- added actor-bound repository reads and same-schema foreign keys so absent
  and cross-actor resources share the not-found behavior;
- added atomic session create/resume, user-message plus `PENDING` invocation,
  exact retry deduplication, request-hash conflict rejection, one bounded
  failed retry, idempotent terminal results, and optimistic session state
  transitions;
- added stable keyset message reads and safe allowlisted tool-call audit
  records without raw arguments, results, prompts, or passages;
- added 90-day session-content purge and retry-key redaction, with safe audit
  removal at 365 days;
- added low-cardinality metrics, body-free logs, disabled-by-default
  configuration, readiness validation, and focused unit/MySQL tests.

Verification:

- Agent Service default suite: 121 passed, 13 skipped;
- combined Agent MySQL 8.4 integration suite: 9 passed, including 4 focused
  persistence cases and 5 existing knowledge regressions;
- Python compilation, Compose validation, whitespace, and scoped secret scans
  passed;
- local Flyway applied V4 and the deployed Agent container is healthy;
- readiness is `READY` with `agentPersistence=DISABLED`.

No authenticated agent API, Product Service listing-eligibility adapter,
orchestration, model call, retrieval call, UI activation, or provider request
was added in AI-CS-01A. At that checkpoint, `AI-CS-01B` through `AI-CS-01E`
remained deferred. No live or paid provider call was made. The normal
base-image rebuild was blocked by the
Docker Hub token endpoint, so the already-local Agent image received the
verified source layer offline and the resulting container is healthy.
Unrelated worktree changes were preserved and no `.env` file was edited.

## AI-CS-01B Implementation Report

Completed on 2026-07-19:

- routed the four `/api/v1/agent/sessions` contracts through the authenticated
  BFF token-relay boundary; the existing BFF security chain protects POST
  commands with session CSRF;
- resolved the app-owned actor through Auth Service from the relayed bearer
  token, rejected client-supplied identity/authorization fields with strict
  schemas, and returned the same hidden `404` for missing and cross-user
  sessions;
- added a constant-time token-protected Product Service customer-service
  listing context based on the current active public individual publication
  version;
- added strict session/message response models, opaque keyset cursors,
  standard error envelopes, safe correlation propagation, and idempotent
  successful-message replay support over the AI-CS-01A repository;
- added forward-safe Agent Flyway V5 to align stored invocation correlation IDs
  with the gateway's 128-character boundary;
- added body-free, low-cardinality API metrics and disabled-by-default
  configuration/readiness behavior;
- added an injected answerer boundary required by the synchronous message
  contract, with the production implementation failing closed until AI-CS-01C.

Verification:

- Agent Service default suite: 130 passed, 13 skipped;
- Agent Service MySQL 8.4 persistence suite: 4 passed;
- API Gateway Maven suite passed;
- focused Product Service MySQL/Testcontainers knowledge and customer-context
  suite passed;
- combined Product Service/API Gateway compile passed;
- Python compilation and scoped whitespace checks passed.

Agent Flyway V5 is the only AI-CS-01B migration and was applied to the local
Agent schema. No RAG orchestration,
retrieval call, provider call, tool execution, UI
activation, evaluation rollout, marketplace write, business workflow, or V2
behavior was added. `AGENT_CUSTOMER_SERVICE_API_ENABLED` remains `false` by
default. If enabled before AI-CS-01C supplies an answerer, readiness reports
`ORCHESTRATION_DEFERRED` and message execution records a safe failed invocation
before returning `503`. No live or paid provider call was made. Unrelated
worktree changes were preserved and no `.env` file was edited.

## AI-CS-01C Implementation Report

Completed on 2026-07-19 as a source-only, disabled-safe Agent Service slice:

- added one listing customer-service orchestrator over the existing 01A/01B
  invocation boundary and AI-RAG-03 `KnowledgeRetriever` protocol;
- implemented only the allowlisted `getListing` and `retrieveKnowledge` read
  tools, with the requesting actor, session listing, current listing version,
  effective time, correlation, and retrieval limits injected by trusted
  runtime code rather than accepted from model or client input;
- restricted orchestration retrieval to `LISTING`. Policy, safety, FAQ, and
  category content remains unavailable rather than being inferred while those
  source-owner or rollout contracts are unresolved;
- added strict tool arguments, listing result, model answer, citation, and
  route-semantic action schemas; citations must match a source returned by the
  current invocation and listing actions must target only the session subject;
- added deterministic no-model paths for prompt injection, private-data
  requests, seller-only decisions, and the approved off-platform transaction
  notice;
- added bounded retrieval query/top-k/context, output-token and timeout
  controls, fail-closed retrieval/model behavior, output contact/secret/URL
  redaction, and safe source precedence instructions;
- persisted only hashed tool arguments/results and bounded source identities,
  using separate sequence slots for the one allowed failed-invocation retry;
- added body-free structured logs, low-cardinality orchestration/tool/model/
  guardrail metrics, mocked-provider tests, and a synthetic listing-only eval
  fixture.

Verification:

- Agent Service default suite passed with 150 tests and 14 Docker-backed tests
  skipped by default;
- focused customer-service API/orchestration suite passed with 24 tests;
- disposable MySQL 8.4 Agent persistence integration suite passed with 5
  tests, confirming the reused invocation/tool-audit foundation;
- Python compilation passed for the changed modules and tests;
- no provider request, API-key picker/setup/creation/rotation/validation flow,
  external OpenSearch operation, runtime refresh, or paid call occurred. After
  the control-center correction, no credential or local environment inspection
  was performed.

No migration, API route, event, gateway, Product, Auth, frontend, Compose,
shared-service, or `.env*` file changed. `AGENT_CUSTOMER_SERVICE_API_ENABLED`
remains `false` by default. With no explicitly injected production model
adapter, readiness continues to report `ORCHESTRATION_DEFERRED`, so normal
marketplace and buyer/seller chat behavior remains independent.

Known limitations and deferred dependencies:

- the current Product agent projection does not yet provide price, currency,
  quantity, condition, public city/region, or negotiability, so 01C does not
  invent those facts;
- policy, safety, FAQ, and category orchestration are deferred until their
  authoritative owner/rollout contracts are approved and enabled;
- the production OpenAI Agents SDK/model adapter, provider activation, live
  evaluation, gateway exposure, and UI activation remain deferred. The model
  boundary in this slice is intentionally interface-and-mock only.

This verified slice started the next AI control-center batch at 1/3. No
successor slice started automatically.

## AI-CS-01C-2 Implementation Report

Completed on 2026-07-19 as a disabled/offline Agent Service-only slice:

- added a strict provider request schema for the question, current listing
  projection, bounded listing passages, and exact allowed source identities;
- bound the 01C `CustomerServiceModel` interface to the existing
  `OpenAIProvider.customer_service_answer` structured Responses operation;
- added an explicit composition factory without wiring it into `create_app`,
  preserving `ORCHESTRATION_DEFERRED` and default-disabled behavior;
- redacted email, phone, secret, URL, exact-address, and coordinate patterns
  before the provider boundary, while retaining the existing output redaction;
- separated fixed instructions from one JSON user-input object and exposed no
  provider tool, general HTTP, database, OpenSearch, browser, shell, or other
  execution capability;
- enforced strict structured output, provider storage disabled, truncation
  disabled, output/context/instruction/serialized-input budgets, and rejection
  of incomplete or malformed provider results;
- preserved typed rate-limit, quota, timeout, model, authentication, and
  provider failure mapping into the existing outage-safe orchestration
  response, with no adapter-owned retry;
- added cancellation handling that records a failed invocation before
  propagating cancellation, preserving retry and tool-audit semantics;
- propagated token and latency usage with estimated cost left at zero because
  the approved provider foundation intentionally has no pricing contract;
- added low-cardinality request/redaction/token metrics and safe logs containing
  correlation, bounded status/usage, and hashes rather than prompts, passages,
  private data, or full provider output.

Verification:

- focused provider foundation suite: 7 passed;
- focused customer-service API/orchestration/adapter suite: 36 passed;
- full default Agent Service suite: 165 passed, 14 Docker-backed tests skipped
  by default;
- Python compilation and scoped whitespace/diff checks passed;
- no disposable MySQL rerun was needed because this slice changed no
  repository behavior, persistence schema, or migration;
- no live provider request, paid call, credential workflow, `.env*` read/edit,
  external OpenSearch/runtime/data operation, or runtime refresh occurred.

No migration, external API, event, gateway, frontend, Product, Auth, shared
service, Compose, dependency, or environment contract changed. The adapter is
available only through explicit dependency injection and is not constructed by
the default application runtime.

Known limitations:

- exact provider cost remains unknown and is recorded as zero until an approved
  pricing metadata or billing-export contract exists;
- semantic faithfulness beyond deterministic citation/action validation still
  requires the later live evaluation and rollout slice;
- Product listing facts remain limited to the existing safe projection, and
  policy, safety, FAQ, and category orchestration remain deferred;
- the provider credential/runtime activation, gateway/UI activation, and
  `AI-CS-01D` remain out of scope.

This verified slice advances the AI lane to 2/3. No successor slice starts
automatically.

## AI-CS-01D-A Implementation Report

Completed on 2026-07-20 as disabled-by-default gateway/frontend source
integration:

- hardened the existing conditional Agent gateway route by removing
  browser-supplied `X-User-Id`, `X-Actor-User-Id`, `X-Keycloak-Sub`, and
  `X-Roles` before token relay; Agent Service continues to derive the actor
  from the authenticated bearer token;
- retained `GATEWAY_FEATURE_AGENT=false`, the default hidden gateway route, and
  the standard correlation and dependency fallback behavior;
- added a separate Angular Agent model/client boundary for the existing four
  AI-CS-01B routes, with exact runtime validation for sessions, messages,
  cursors, sources, and the three allowlisted action types;
- added explicit account Agent routes before the buyer/seller conversation
  parameter route; disabled production/default routes redirect without loading
  an Agent component or making an Agent request;
- activated the existing floating-chat and account agent rows only when the
  existing committed frontend capability is explicitly true; both rows remain
  hidden under the false production and development defaults;
- added one reusable listing-bound Agent thread with deterministic listing-ID
  selection, session refresh, loading and model-thinking states, safe outage
  and unavailable-listing states, retry using the original
  `clientMessageId`, grounded source labels/IDs/versions, and visible AI
  provenance;
- rendered only server-validated `MESSAGE_SELLER`, `VIEW_LISTING`, and
  `BROWSE_MARKETPLACE` actions. Seller handoff starts the existing
  buyer/seller conversation only after the user chooses the action and never
  sends a message automatically;
- kept Agent DTOs, routes, state, and messages separate from `ChatService`
  buyer/seller DTOs. Agent threads contain no completion progress, mark-done,
  buyer-confirmation, offer, negotiation, or trade controls.

Verification:

- focused Angular Agent/route/floating/account suite passed;
- complete Angular suite passed with 369 tests;
- Angular production build passed within configured bundle/style budgets;
- API Gateway suite passed with 75 tests, plus 16 `common-web` reactor tests;
- the focused gateway upstream test verified authentication and correlation
  propagation, stripped spoofed identity/role headers, guest rejection, and no
  upstream request for unauthenticated access;
- Gateway Java compilation and scoped static/diff checks passed.

No migration, API schema, event, Agent Service, Product, Auth, Chat Service,
OpenSearch, Compose, dependency, environment, runtime, fixture, or provider
configuration changed. No provider credential workflow, external evaluation,
runtime refresh, live/paid provider request, or OpenSearch operation occurred.

Default-disabled evidence:

- committed production and development `aiAssistant` flags remain false and
  were not edited;
- committed `GATEWAY_FEATURE_AGENT` fallback remains false and was not edited;
- default Agent account paths redirect to `/account/messages`;
- floating/account Agent rows do not render under the default flag;
- default UI regressions assert no Agent session/message request;
- the normal marketplace and buyer/seller chat suites pass independently.

Known limitations and deferred dependencies:

- the global entry accepts an exact listing ID and links to marketplace browse;
  a richer Product-backed listing picker and listing-detail contextual launch
  require a later bounded frontend slice;
- gateway and frontend capability flags plus Agent runtime readiness must be
  coordinated only after AI-CS-01E evaluation/rollout approval;
- no live semantic, provider, latency, cost, or cohort evaluation was
  authorized in this slice.

This verified slice advances the AI lane to 3/3. Mandatory AI-only cleanup is
next and requires a separate Control Center assignment. No successor feature
started automatically.

## AI-CS-CLEAN-P0-01 Cleanup Report

Completed on 2026-07-20 as the mandatory behavior-preserving cleanup after
`AI-CS-01C`, `AI-CS-01C-2`, and `AI-CS-01D-A`:

- removed raw actor and listing identifiers from the Agent listing-eligibility
  rejection log, retaining only a bounded event name and correlation ID;
- removed a redundant orchestration latency branch while preserving the
  persisted end-to-end latency value;
- aligned Angular question-conflict handling with the existing Agent API error
  contract: only missing/read-only sessions become read-only, in-progress
  idempotent messages retain their original retry key, and terminal
  request-key conflicts stop offering an invalid retry;
- re-audited provider payload separation, citation/action validation,
  cancellation propagation, bounded metrics/audits, gateway spoof-header
  stripping, Agent route precedence, and disabled UI behavior without changing
  their approved contracts;
- corrected the stale roadmap next-task text and reset the AI lane from 3/3 to
  0/3.

Verification:

- Agent default suite: 166 passed, with 14 expected opt-in integration skips;
- focused customer-service/orchestration/provider suite: 37 passed;
- disposable MySQL 8.4 Agent persistence suite: 5 passed;
- API Gateway suite: 75 passed, plus 16 `common-web` tests, and the Maven
  production package completed;
- focused Angular Agent/gateway-UI integration suite: 49 passed;
- complete Angular suite: 371 passed;
- Angular production build and scoped Python compilation/static/diff checks
  passed;
- changed-file credential and unsafe-content log scans found no credential,
  prompt, passage, response body, or actor/listing identifier leakage in the
  cleaned customer-service files.

No API, persistence, migration, tool, provider, retrieval, Product/Auth,
OpenSearch, runtime, activation, or environment contract changed. No live or
paid provider request was made. The next AI feature requires a separate PM
assignment.

## AI-CS-01E-A Offline Evaluation Baseline Report

Completed on 2026-07-20:

- added a strict 14-case fixture, deterministic local runner, and
  machine-readable report/JSON Schema;
- exercised the real listing-only retriever and customer-service orchestrator
  using only in-memory embedding, OpenSearch-style, persistence-audit, and
  provider fakes;
- measured retrieval relevance/recall, fixture-annotated claim faithfulness,
  citation validity/completeness, rejected-source safety, cross-user
  isolation, allowlisted-tool boundaries, safe failure behavior, outcome
  conformance, and simulated offline latency;
- recorded deterministic zero-cost metadata with no approved pricing and kept
  release blocked despite the passing offline baseline;
- added regressions for strict schemas, determinism, every threshold family,
  default-off gates, stale/deleted/cross-listing rejection, outage handling,
  and safe report/log content.

Verification:

- focused offline evaluation suite: 12 passed;
- full default Agent suite: 178 passed with 14 expected opt-in MySQL/OpenSearch
  integration skips;
- Python compilation, fixture/report schema generation, diff/whitespace, and
  changed-file credential-pattern scans passed;
- the baseline passed all provisional offline thresholds across 14 cases with
  simulated p95 latency `240 ms`, zero provider requests/tokens/cost, and a
  `BLOCKED` release decision.

No migration, product API, runtime configuration, provider credential, live
provider call, external OpenSearch request, gateway/frontend activation,
cohort, dashboard, or rollout behavior was added. Live semantic quality,
production latency, real cost, and rollout approval remain deferred. The AI
lane advances from 0/3 to 1/3 and no successor starts automatically.

## AI-CS-01E-B Release Gate And Offline Observability Report

Completed on 2026-07-20:

- added strict release input, decision, approval, switch, gate-result, and
  offline-observability schemas;
- added deterministic report timestamp/digest, schema, consistency, recency,
  version, threshold, and fake-failure coverage gates;
- made each explicit default-off or kill-switch state authoritative and
  blocking;
- required bounded current external evidence for production quality, latency,
  and cost without treating simulated latency or zero-cost metadata as
  production evidence;
- defined sequential internal, small-cohort, and wider-rollout approvals plus
  fixed rollback triggers, without creating cohorts or activating flags;
- added the committed default-blocked input fixture with every real switch and
  approval false.

Verification:

- focused 01E-B release-gate suite: 16 passed;
- combined 01E-A/01E-B evaluation suite: 28 passed;
- full default Agent suite: 194 passed with 14 expected opt-in MySQL/OpenSearch
  integration skips;
- Python compilation, three schema CLI paths, and the default-blocked CLI path
  passed;
- diff/whitespace, changed-file credential-pattern, and unsafe-log checks
  passed.

No migration, runtime dashboard, feature flag, cohort, provider request,
credential workflow, pricing assumption, external OpenSearch request,
gateway/frontend/Product/Auth change, environment read/edit, or runtime
activation was added. All real flags remain false. The AI lane advances from
1/3 to 2/3 and no successor starts automatically.

## AI-CS-01E-C Default-Off Listing Context Picker And Detail Launch Report

Completed on 2026-07-20:

- replaced the exact listing-ID entry with an image-forward selector over the
  existing approved public individual-listing search projection;
- limited picker output and listing-detail route context to normalized listing
  ID plus bounded, control-character-free public title display text;
- started the existing authenticated Agent create-or-resume flow only after an
  explicit result selection or listing-detail launch; no question, seller
  message, trade command, or other automatic action is sent;
- added loading, empty, safe error/retry, unavailable-listing, keyboard-label,
  focus-visible, reduced-motion, and narrow-screen no-overflow behavior;
- kept selector, detail launch, Agent route, and Agent requests absent under
  the committed false `aiAssistant` capability.

Verification:

- focused Agent, route, listing-detail, and floating-chat suite: 57 passed;
- full Angular suite: 381 passed;
- Angular production build passed within configured budgets;
- diff/whitespace and changed-file credential/unsafe-log checks passed.

No Product, Search, Auth, Agent API, gateway, chat, provider, environment,
migration, runtime, cohort, or release-gate contract changed. External
activation remains blocked by the AI-CS-01E-B release evaluator. This advances
the AI lane from 2/3 to 3/3; mandatory AI-only cleanup is the next control-center
assignment and no successor starts automatically.

## AI-CS-CLEAN-P0-02 Evaluation/Release-Gate/Listing-Context Cleanup Report

Completed on 2026-07-20 as the mandatory behavior-preserving cleanup after
`AI-CS-01E-A`, `AI-CS-01E-B`, and `AI-CS-01E-C`:

- made the offline report reject duplicate fixed-metric thresholds and require
  its baseline status and failure reasons to match the evaluated cases and
  thresholds exactly;
- made the observability report reject duplicate fixed metric/dependency
  identities and the release decision require exactly one result for every
  fixed gate;
- preserved the distinction between simulated offline latency/zero-cost
  metadata and unknown production latency/cost;
- consolidated listing ID/title normalization into one ID/title-only selection
  boundary used by the picker, Agent page, thread, and listing-detail launch;
- cleared one-time listing query context from the URL after capture and added
  removed-listing, privacy, and no-automatic-action regressions without changing
  the Agent or public-listing contracts.

Verification:

- focused offline evaluation and release-gate suite: 30 passed;
- full default Agent suite: 196 passed with 14 expected opt-in
  MySQL/OpenSearch integration skips;
- Python compilation, report/schema CLI paths, and the committed
  default-`BLOCKED` release input passed;
- focused Agent listing-context, route, listing-detail, and floating-chat
  Angular suite: 58 passed;
- full Angular suite: 382 passed;
- Angular production build passed within configured budgets;
- scoped diff/whitespace, credential-pattern, and unsafe-log checks passed.

No migration, API, provider request, network call, runtime flag, cohort,
rollout, Product/Search/Auth behavior, seller message, model question, or trade
action was added. All capability states remain false by default. Production
quality, latency, cost, and rollout approvals remain external blockers. This
resets the AI lane from 3/3 to 0/3; no `AI-LC-01`, rollout, or successor slice
starts automatically.

## AI-CS-CLEAN-P0-03 LangChain Chat And Stabilization Cleanup Report

Completed locally on 2026-07-20 as the mandatory behavior-preserving cleanup
after `AI-CS-02B`, `AI-CS-02C`, `AI-CS-STAB-P1-01`, and
`AI-CS-STAB-P1-03`:

- kept `langchain-core==1.4.9` behind the existing `QuestionAnswerer` and
  application-owned retrieval/provider boundaries, with no LangChain type,
  memory, or checkpointer in HTTP, BFF, OpenAPI, or MySQL persistence;
- retained strict default-off generation precedence and the existing MySQL
  session/message/invocation authority;
- made the demo Agent Service wait for successful Agent migrations and healthy
  OpenSearch while keeping index bootstrap a separate explicit operation;
- kept the normal production/development frontend network-silent and
  `demo-ai` as the only explicit UI opt-in;
- retained coalesced BFF session/CSRF warmup before Agent writes, exact
  idempotency keys, no automatic replay after an uncertain POST, and the
  existing login-return path for known authentication expiry;
- consolidated validation diagnostic categories with their metric allowlists,
  reduced expected invalid-request diagnostics to informational severity, and
  retained only bounded fixed categories, allowlisted key presence, correlation
  ID, and `subject.id` runtime type/length without values;
- aligned the frontend create-session regression with the real Product listing
  ID `01D00000000000000000000101` and preserved the exact strict request
  contract.

The malformed deployed browser request that motivated the temporary diagnostic
slice has not been reproduced from current local source. The diagnostics remain
privacy-safe evidence for one separately approved deployed browser attempt;
they are not a contract relaxation or rollout evidence.

Verification:

- focused LangChain/runtime/customer-service/config/demo Agent tests: 52
  passed;
- full default Agent suite: 321 passed with 15 expected opt-in
  MySQL/OpenSearch integration skips, plus Python compilation and dependency
  consistency checks;
- focused Agent customer-service Angular tests: 33 passed; full Angular suite:
  426 passed; normal production and explicit `demo-ai` builds passed;
- full gateway/shared offline package: 80 tests passed, including the existing
  Agent bearer relay, spoof-header stripping, correlation, authentication, and
  default-off route regressions;
- normal, `ai`, and `ai-bootstrap` Compose configurations validated with
  automatic environment-file loading disabled and without starting services;
- scoped diff/whitespace, credential-pattern, dependency-boundary,
  default-off, and unsafe-log/cardinality checks passed. No provider request
  occurred.

One existing shared Auth boundary remains deliberately unchanged:
`AuthService` converts a BFF session-load transport failure into an
unauthenticated snapshot. Agent writes still fail closed with zero Agent POST,
but that preflight cannot distinguish a temporary Auth outage from a signed-out
session without a separately approved shared Auth contract change.

This repository follows a local-first, batched-deployment policy: source,
tests, and cleanup are completed before a separate task owns deployment,
environment values, and browser verification. All committed capability flags
remain false and release remains `BLOCKED`. The AI lane resets from `3/3` to
`0/3` and pauses. The next approved direction is the separately dispatched,
local-only `AI-DISC-01A` baseline recorded in the general AI roadmap; no
discovery code, dependency, runtime, or completion status is added by this
cleanup.
