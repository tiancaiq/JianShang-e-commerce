# AI-00 Agent And Automated Operations Plan

Status: complete as a documentation-only planning slice.

Release: V3.

## Purpose

AI-00 defines the product contract, service boundaries, tool rules, evaluation
gates, and implementation order for three related capabilities:

- a listing-bound customer-service assistant;
- seller listing-content suggestions from uploaded images;
- AI-assisted classification of customer listing reports followed by narrowly
  allowlisted automated operations.

This plan does not authorize V3 implementation during active MVP work. It
prepares future slices after the current MVP foundation is stable.

## Existing Requirement Mapping

The plan builds on approved requirements and deferred contracts:

- `AI-RAG-00` for source ownership, precedence, retrieval, citation, retention,
  and privacy contracts;
- `AI-01` for tool-grounded listing search and detail reads;
- `AI-03` for seller-confirmed listing-content drafts;
- `ADM-03` for the reports queue;
- `ADM-04` for separately authorized suspension and restoration;
- the implemented `ADM-REP-00/01/02` intake and admin-triage APIs;
- the V3 agent session APIs and allowlisted tool model.

A listing-bound customer-service assistant and automated report operations need
new requirement-level approval before implementation. They are not silently
added to the current MVP contract by this planning slice.

## Proposed Product Contract

### Listing customer-service assistant

The initial assistant is a clearly labeled platform AI assistant, not the
seller and not an unrestricted participant in buyer/seller chat.

Initial rules:

- A session is bound to one active, approved individual listing.
- The initial release is authenticated-only so actor-level rate limits, abuse
  controls, and audit records are reliable.
- The existing `Marketplace agent` rows in the floating chat launcher and
  `/account/messages` provide the UI. Agent sessions reuse chat presentation
  patterns without using buyer/seller chat persistence or authorization.
- Answers combine current public listing facts from Product Service with
  filtered, source-attributed public knowledge from the agent-owned OpenSearch
  projection.
- Current structured listing facts override conflicting vector content.
- Factual answers identify the source ID and version used, and UI actions are
  validated against a deterministic allowlist.
- The assistant states when requested information is missing or uncertain.
- The assistant cannot negotiate, accept a deal, promise availability, verify
  payment, claim platform payment protection, or create a trade.
- Questions requiring seller knowledge route the user to the existing
  `LISTING_BUYER_SELLER` conversation flow.
- Agent output is never inserted into buyer/seller chat as if the seller wrote
  it.
- OpenAI unavailability removes the assistant entry point or returns a
  temporary error without affecting listing detail or seller chat.

The first implementation starts with one agent. Specialist agents, handoffs,
web search, file search, shell access, and sandbox access are out of scope.

### Seller image-to-listing assistant

The seller assistant operates only on a seller-owned listing draft and media
already authorized through the listing media flow.

Initial rules:

- The agent receives listing and image data only through an allowlisted
  application tool that rechecks actor ownership and draft eligibility.
- The result uses a strict schema for suggested title, description, category
  candidates, attributes, alt text, uncertainties, and warnings.
- Brand, model, authenticity, safety, condition, and completeness are not
  asserted when the image does not establish them reliably.
- The result remains a proposal. The seller reviews and edits it before the
  existing versioned listing update API persists anything.
- The agent does not publish or submit a listing for moderation.

### Automated report operations

The system classifies whether a listing report is substantiated under
marketplace policy. It does not claim to decide whether conduct or content is
legal. Suspected illegal activity, credible threats, or jurisdiction-dependent
questions require trust-and-safety or legal review.

The proposed first autonomous outcomes are:

- `CLOSE_DUPLICATE`: close or merge a duplicate report without changing the
  reported listing;
- `CLOSE_INSUFFICIENT_EVIDENCE`: close a report when the submitted evidence
  cannot establish the selected policy violation;
- `TEMPORARILY_RESTRICT_LISTING`: remove a listing from public discovery while
  preserving history and creating immediate staff-review and restoration
  paths;
- `ESCALATE`: create or route staff review work without changing the reported
  subject.

`TEMPORARILY_RESTRICT_LISTING` without per-case staff confirmation is a
proposed contract change and requires explicit approval before implementation.
It must be feature-flagged and limited to enumerated policy rules with
validated evidence and approved confidence thresholds.

The initial automated workflow cannot:

- permanently delete or permanently remove a listing;
- suspend a user or business;
- publish or approve content;
- inspect unrestricted chat history;
- settle a dispute or make a legal determination;
- execute a free-form action selected by the model.

An AI model returns a structured assessment. A deterministic Java policy
evaluator owns the action decision and may execute only an allowlisted command.
Every action is idempotent, audited, reversible, and protected by optimistic
locking. OpenAI failure leaves reports queued for staff review and disables
automatic restriction.

## Service And Data Ownership

### Agent service

The isolated V3 `agent-service` owns:

- agent sessions and messages;
- agent instructions and tool registry;
- model invocation metadata;
- tool-call audit metadata;
- latency, usage, cost, and result status;
- prompt and output safety policy;
- agent evaluation fixtures and results.

Recommended runtime: Python, FastAPI, OpenAI Agents SDK, Responses API, and
Pydantic schemas. This is a deliberate isolated exception to the Java backend
baseline and requires an architecture decision before implementation.

The agent service has no product, chat, identity, report, or moderation
database credentials. It calls allowlisted application APIs with the
requesting actor's context.

For customer-service RAG, the agent service also owns deterministic chunking,
embedding, the versioned OpenSearch knowledge index and alias, filtered
retrieval, source-attributed answer validation, and index lag/deletion
operations. OpenSearch is rebuildable and never authoritative.

### Product and moderation ownership

The existing Java/Spring Boot product and moderation boundary remains
authoritative for:

- listing ownership, status, visibility, and version;
- listing media authorization;
- report persistence and evidence references;
- marketplace policy versions and action allowlists;
- report assessments used for operational decisions;
- temporary restrictions, restoration, appeals, and audit history;
- transactional outbox publication for resulting domain events.

MySQL remains authoritative. Redis may hold rate-limit and short-lived agent
state. Kafka may carry approved durable events through an outbox. OpenSearch
and Redis never become authoritative for reports or moderation actions.

## Proposed Agent Tools

The first customer-service and listing-draft slices may expose only:

| Tool | Actor | Effect |
|---|---|---|
| `getListing` | Authenticated marketplace user | Read current approved public listing data |
| `retrieveKnowledge` | Authenticated marketplace user | Read bounded approved public passages filtered to trusted session context |
| `getMyListingDraftContext` | Owning individual seller | Read one owned eligible draft and selected media |
| `draftListingContent` | Owning individual seller | Return a structured proposal without persistence |

Tool arguments use strict schemas. Each application API independently verifies
identity, ownership, resource state, and permitted fields. Tool results expose
no email, phone, exact address, storage credential, object key, internal
moderation data, or unrestricted user content.

`getMarketplacePolicy` is not a separate customer-service tool. Versioned
marketplace policy, safety guidance, FAQs, listing content, and category
guidance are retrieved through `retrieveKnowledge` under the ownership and
filter rules approved by `AI-RAG-00`.

The model cannot supply actor identity, subject listing identity, visibility,
policy time, or unrestricted OpenSearch filters. Those values come from
trusted runtime context. No general HTTP, database, browser, web-search,
file-search, MCP, shell, or sandbox tool is exposed.

The report classifier is not exposed as a user-callable agent tool. It is an
internal structured model operation invoked by the report workflow. The model
cannot call enforcement commands.

## Proposed API Direction

Existing V3 session routes remain the starting point:

```text
POST /api/v1/agent/sessions
POST /api/v1/agent/sessions/{sessionId}/messages
GET  /api/v1/agent/sessions/{sessionId}
```

`POST /api/v1/agent/sessions` needs a future `LISTING_CUSTOMER_SERVICE`
session type and subject listing ID. Session creation must verify that the
listing is currently visible and eligible.

Customer-service assistant messages use a structured response containing:

- `resolutionType`: `ANSWERED`, `PARTIAL`, `UNKNOWN`, `CONTACT_SELLER`, or
  `REFUSED`;
- validated source type, ID, version, and label entries;
- validated route-semantic actions from `MESSAGE_SELLER`, `VIEW_LISTING`, or
  `BROWSE_MARKETPLACE`.

The frontend never parses free-form answer text to infer an action.

The listing-content contract should remain proposal-only. The future request
identifies an owned listing draft and selected media IDs; the response returns
structured suggested fields plus uncertainty metadata. Applying selected
fields continues to use the existing listing `PATCH` contract with `If-Match`.

Report intake and human admin triage are implemented by `ADM-REP-00/01/02`:

```text
POST /api/v1/reports
GET  /api/v1/admin/reports
POST /api/v1/admin/reports/{reportId}/claim
POST /api/v1/admin/reports/{reportId}/dismiss
POST /api/v1/admin/reports/{reportId}/ready-for-investigation
```

Automated classification remains a future internal workflow, not a public
endpoint. The implemented triage commands never invoke enforcement; future
case decisions and separately authorized enforcement commands remain the
authoritative human-controlled paths.

## Safety And Operational Controls

Required controls before any production agent traffic:

- prompt, tool, policy, model, and schema versions recorded per run;
- sensitive model and tool data excluded from unrestricted logs and traces;
- per-user, per-listing, and per-IP rate limits;
- timeouts, bounded retries, and circuit breaking for OpenAI calls;
- feature flags by capability and autonomous action;
- a global automated-operation kill switch;
- bounded tool-call count, token usage, output size, and execution time;
- prompt-injection handling for listing text, images, and report descriptions;
- mandatory source-type, subject-listing, visibility, language, effective-date,
  version, and invalidation filters for vector retrieval;
- source-precedence and citation validation before an answer is stored;
- 90-day inactive-session message-content retention and 365-day safe
  invocation/tool metadata retention;
- source invalidation and deletion propagation with an initial 15-minute
  operational target and a vector-answer kill switch for excessive lag;
- explicit restoration and appeal paths for temporary restrictions;
- dashboards for error rate, latency, cost, escalation rate, restriction rate,
  restoration rate, and confirmed false positives.

## Evaluation Gates

The implementation must include a focused eval set that exercises the real
agent and classification paths.

Customer-service cases:

- answer grounded in current listing fields;
- policy and knowledge answers grounded in retriever passages with correct
  source versions;
- structured listing facts override conflicting vector content;
- cross-listing, visibility, stale-version, invalidation, and deletion filters;
- missing information produces uncertainty instead of invention;
- listing text attempts prompt injection;
- user asks the assistant to negotiate or accept a deal;
- user requests private contact or exact location data;
- listing becomes unavailable during a session;
- OpenAI or an application tool is unavailable.

Listing-content cases:

- clear product image produces useful structured suggestions;
- ambiguous image does not invent brand, model, authenticity, or condition;
- multiple images contain conflicting evidence;
- image or listing text contains prompt-injection content;
- cross-user draft or media access is rejected;
- seller application preserves optimistic locking and explicit confirmation.

Report-operation cases:

- valid, invalid, duplicate, malicious, and insufficient-evidence reports;
- ambiguous or severe evidence always escalates;
- the model proposes an unsupported action;
- repeated processing produces one operational effect;
- listing version changes before action execution;
- temporary restriction can be restored with complete history;
- AI outage queues staff work without changing listing state;
- false-positive and restoration thresholds prevent rollout expansion.

Exact wording is not an evaluation target unless it is contractual. Evals
grade grounding, schema validity, tool calls, forbidden behavior, action
selection, audit records, and state transitions.

## Future Slice Order

1. `AI-00` agent and automated-operations plan.
2. `AI-LLM-01` OpenAI provider foundation.
3. `AI-RAG-00` hybrid-RAG contract reconciliation.
4. `AI-RAG-01` OpenSearch vector foundation.
5. `AI-RAG-02` ingestion and embedding pipeline.
6. `AI-RAG-03` filtered knowledge retriever.
7. `AI-CS-01A` agent persistence.
8. `AI-CS-01B` authenticated agent APIs.
9. `AI-CS-01C` hybrid-RAG orchestration.
10. `AI-CS-01D` existing chat UI activation.
11. `AI-CS-01E` evaluation and rollout.
12. `AI-LC-01` LangChain integration after the measurable baseline.
13. `AI-LIST-01` seller image-to-listing content proposal.
14. `AI-LIST-02` seller proposal review and confirmed Product application.
15. `REP-00` listing report domain and policy taxonomy.
16. `REP-01` listing report intake, evidence, and persistence.
17. `ADM-REP-01` user-reported listing queue and admin override paths.
18. `AI-REP-01` report classification in shadow mode.
19. `OPS-REP-01` allowlisted reversible report operations.
20. `OPS-REP-02` measured policy expansion after false-positive and restoration
   gates pass.

Each implementation slice needs its own requirement mapping, API contract,
database ownership, authorization rules, tests, logs, metrics, and feature
flag. Multiple slices must not be implemented together merely because they
share this plan.

## Approved And Deferred Decisions

Approved for future planning:

1. Use Python/FastAPI and the OpenAI Agents SDK for the isolated agent service.
2. Keep the initial customer-service assistant authenticated-only.
3. Reuse the existing marketplace chat UI presentation for agent sessions
   without merging agent and buyer/seller domain state.
4. Use Product Service for current listing facts and an agent-owned OpenSearch
   projection for filtered approved knowledge.
5. Keep initial report classification in shadow/copilot mode.

Deferred until report shadow-mode results are reviewed:

- whether `TEMPORARILY_RESTRICT_LISTING` may execute without per-case staff
  confirmation.

## AI-00 Completion Criteria

- Product boundaries for all three workflows are documented.
- Agent and authoritative application ownership are separated.
- Initial tool and API direction is documented.
- Autonomous actions are bounded, reversible, and identified as requiring
  explicit approval.
- Eval and operational rollout gates are documented.
- Future work is divided into small roadmap slices.
- No V3 application code, migration, runtime dependency, or environment change
  is introduced.
