# AI-LIST-02 Seller Proposal Review And Confirmed Application Plan

Status: `AI-LIST-02A` implemented and verified on 2026-07-20;
`AI-LIST-02B/C` not started. AI lane `1/3`.

Release: V3.

Requirement: `AI-03 Draft seller listing content`.

## 1. Purpose

Turn the completed, default-off AI listing proposal foundation into a
seller-controlled workflow without making AI authoritative for listing data.

`AI-LIST-01A/B/C` already provides:

- a strict proposal-only schema;
- offline and fake-transport multimodal orchestration;
- Product-authorized owned-draft media reads;
- bounded image, provider, privacy, injection, replay, audit, and metric
  behavior; and
- default-off, unwired composition with no Product write.

`AI-LIST-02` adds the missing product path:

```text
seller chooses owned draft/media
  -> AI generates a proposal
  -> seller reviews and edits selected fields
  -> seller explicitly confirms
  -> existing Product PATCH applies the seller's chosen values
```

The AI result is never submitted for moderation or published automatically.

## 2. Product Invariants

1. Product Service remains authoritative for listing ownership, editable
   state, versions, validation, category IDs, and every write.
2. Agent Service may store a bounded proposal and safe audit metadata, but the
   proposal is not listing state.
3. The actor, listing ID, listing version, and selected media IDs come from
   authenticated application context, never model output.
4. Seller confirmation is required before a Product write.
5. The confirmed write uses the existing
   `PATCH /api/v1/listings/{listingId}` contract with `If-Match`.
6. A stale listing version returns the normal Product version conflict and
   never overwrites seller changes.
7. Seller edits override AI suggestions. The applied payload contains the
   seller-selected final values, not a replay of hidden model output.
8. AI cannot submit, approve, activate, publish, price, locate, or create
   inventory for the listing.
9. Ordinary listing editing remains fully usable when AI is disabled or
   unavailable.

## 3. Current Progress And Entry State

The AI lane is at `1/3` after verified `AI-LIST-02A`.

Release remains blocked because:

- Agent and Product media/proposal gates are false and unwired;
- no review UI exists;
- no confirmed application path exists;
- live multimodal quality, latency, cost, and privacy evidence is unknown; and
- no rollout approval or cohort exists.

`AI-LC-01A` stopped cleanly because LangChain was absent. `AI-LIST-02` must
continue through existing application interfaces and must not install or
expose LangChain types.

## 4. Slice Sequence

### 4.1 AI-LIST-02A Seller Proposal Session And Review API

Owner: Agent Service, with only the already approved Product media read.

Adds a default-disabled authenticated proposal API:

```text
POST /api/v1/agent/listing-proposals
GET  /api/v1/agent/listing-proposals/{proposalId}
POST /api/v1/agent/listing-proposals/{proposalId}/dismiss
```

The create request carries:

- owned listing ID;
- expected listing version;
- one through four selected media IDs;
- client request ID; and
- strict schema version.

The actor is derived from the authenticated BFF session. Agent Service calls
the existing Product media tool, which independently rechecks ownership,
editable state, listing/media versions, and bytes.

The response contains:

- proposal ID and proposal schema version;
- listing ID and exact source listing version;
- selected-media fingerprints and evidence IDs;
- suggested title, description, and category labels;
- confidence and explicit unknowns;
- `proposalOnly=true`;
- `requiresSellerConfirmation=true`;
- creation and expiry timestamps; and
- safe provider/result metadata allowed by the existing contract.

Agent Service may persist only the bounded structured proposal needed to
resume review. Proposal content expires no later than the existing 90-day
Agent content-retention ceiling; implementation should choose a shorter
review expiry and document it before adding a migration. Safe hashes, versions,
result status, latency, token, and correlation metadata follow the existing
Agent audit-retention contract.

Create is replay-safe by actor, listing, client request ID, listing version,
and selected-media fingerprint. Same key/same request returns the existing
proposal; same key/different request returns a conflict. Cross-actor reads and
missing proposals use the same hidden response.

It does not call Product PATCH or expose a write tool.

Green `AI-LIST-02A` advances the AI lane to `1/3`.

Completion evidence: Agent Flyway V6, authenticated create/get/dismiss routes,
DB-coordinated replay claims, hidden ownership, 24-hour content purge,
90-day tombstones, default-off gate precedence, strict schemas, safe hashed
observability, and offline/MySQL regressions were implemented and verified on
2026-07-20. No Product write, runtime activation, network, or live provider
request was added.

### 4.2 AI-LIST-02B Seller Proposal Review UI

Owner: Angular marketplace account/listing experience plus the existing BFF
route family. It is not a business-seller portal page.

The responsive accessible review UI:

- starts only from an owned individual listing in `DRAFT` or
  `CHANGES_REQUESTED`;
- lets the seller select eligible images;
- shows proposal evidence, confidence, and unknown fields;
- presents title, description, and category suggestions as editable form
  values;
- lets the seller keep, edit, or discard each suggestion;
- clearly labels unsupported price, location, condition, quantity,
  negotiability, identity, authenticity, and safety fields as not inferred;
- provides explicit `Apply selected fields` and `Dismiss` actions;
- warns when the listing version changed since proposal generation; and
- falls back to the normal listing editor on AI outage.

Disabled behavior is hidden and network-silent. Loading the ordinary listing
editor must make zero Agent requests.

This slice does not apply the proposal. The Apply action remains disabled or
routes to a non-mutating review confirmation until 02C is green.

Green `AI-LIST-02B` advances the AI lane to `2/3`.

### 4.3 AI-LIST-02C Confirmed Versioned Product Application

Owner: existing Angular listing editor and Product Service PATCH contract.
Agent Service remains outside the authoritative write.

After the seller explicitly confirms:

1. the UI constructs a normal Product PATCH from only the seller-selected
   final values;
2. `If-Match` carries the source listing version;
3. Product Service rechecks the authenticated owner and editable state;
4. Product performs its normal field/category validation;
5. Product writes one ordinary listing version and history/audit context;
6. a successful response updates the editor with the new version; and
7. an optional Agent outcome acknowledgement records only proposal ID,
   applied/dismissed/conflict outcome, resulting listing version, safe hashes,
   and correlation ID.

The outcome acknowledgement is not authoritative and cannot make a failed
Product write appear successful. It is replay-safe and contains no raw Product
payload, media bytes, prompt, provider body, or unrestricted seller data.

Version conflict returns the current listing to the seller for comparison.
The system never retries a stale write automatically.

This slice still does not submit the listing for moderation, activate it,
publish it, or infer fields outside the approved proposal schema.

Green `AI-LIST-02C` advances the AI lane to `3/3` and immediately triggers the
mandatory AI-only cleanup.

## 5. API And Persistence Boundaries

The authoritative `AI-LIST-02A` baseline is finalized as follows:

- external routes are the three `/api/v1/agent/listing-proposals` routes in
  `docs/mvp/api-contract.md`; the existing default-off Agent gateway route,
  bearer relay, spoof-header stripping, correlation envelope, and POST CSRF
  policy apply without a gateway source change;
- create is strict `LISTING_PROPOSAL_V1`, limited to 8 KiB, and accepts only
  canonical listing/version/media/client-request fields;
- response content reuses the strict AI-LIST-01 proposal and includes only
  verified media evidence plus bounded instruction/schema/result/usage
  metadata;
- states are `READY`, `DISMISSED`, and `EXPIRED`, with only
  `READY -> DISMISSED|EXPIRED`;
- V6 adds terminal proposal rows, short-lived DB generation claims, and
  dismiss idempotency rows inside the Agent schema;
- proposal/evidence/result content expires exactly 24 hours after creation,
  dismissal purges immediately, hourly and lazy purge are bounded, safe
  idempotency tombstones last 90 days, and existing safe audit metadata remains
  capped at 365 days;
- create deduplicates by `(actorUserId, clientRequestId)` plus a canonical hash
  over schema/listing/version/sorted media, while dismiss deduplicates by
  `(actorUserId, proposalId, Idempotency-Key)` with a fixed DISMISS hash; and
- direct API disablement precedes actor and persistence work; exact replay
  precedes generation gates; a new create then checks kill switch,
  orchestration, Product media, and multimodal provider in that order.

All listing-proposal and generation flags remain false by default. Release
remains blocked and source completion does not authorize runtime activation.

Do not add Agent types to Product API contracts. Do not add OpenAI or
LangChain types to persistence.

Product Service receives no proposal content until the seller converts
selected suggestions into its existing PATCH request.

## 6. Guardrails And Observability

Every custom application-tool invocation keeps the existing pre/post boundary
checks:

- actor and listing scope;
- strict arguments and result schema;
- media ownership/state/integrity;
- prompt/instruction separation;
- output evidence/unknown consistency;
- contact/secret/location redaction;
- token, image, time, and output budgets;
- cancellation and replay cleanup; and
- safe result classification.

Logs and metrics contain no raw prompt, proposal body, media bytes, storage
references, seller PII, Product patch body, or provider response body.

Trace or audit exports must default to sensitive-content exclusion. A proposal
ID, actor/listing hashes, listing/media versions, selected-field names, result
status, latency, token counts, and correlation ID are sufficient for
operations.

## 7. Verification By Slice

### AI-LIST-02A

- create/get/dismiss happy paths;
- actor/listing/media isolation;
- default-off zero Product/provider calls;
- same-request replay and request-hash conflict;
- stale listing/media version;
- expiry and purge;
- provider timeout/outage/malformed result;
- cancellation during media and provider calls;
- proposal schema/evidence/unknown validation;
- safe persistence and logs; and
- full Agent tests plus focused Product media regressions.

### AI-LIST-02B

- accessibility and keyboard flow;
- responsive listing/media/review layout;
- disabled and outage fallback;
- no Agent request from normal listing editing;
- seller edits override proposal fields;
- unsupported fields remain uninferred;
- version-change warning;
- dismissal and retry; and
- focused plus full Angular tests and production build.

### AI-LIST-02C

- explicit confirmation required;
- selected fields only;
- Product owner/editable-state enforcement;
- `If-Match` success and stale conflict;
- replay-safe outcome acknowledgement;
- Product failure cannot be reported as applied;
- no automatic submit/publish;
- normal listing editor still works without AI; and
- Product/Agent/Angular focused tests and applicable full suites.

## 8. Activation And Release Gates

Source completion does not authorize live activation.

The capability may enter a limited authenticated local cohort only after:

- all 02A/B/C and cleanup tests pass;
- deterministic offline proposal quality remains at or above the approved
  baseline;
- real multimodal quality, latency, token, and cost evidence is collected;
- privacy review approves image and proposal handling;
- Product and Agent kill switches are proven;
- ordinary listing creation/editing is independent;
- rollback removes the AI entry point without affecting listing data; and
- PM explicitly approves the cohort.

AI remains unable to publish or submit listings in every cohort.

## 9. Cross-Lane Coordination

`AI-LIST-02A` may run in parallel with the Order/Auth backend-only
`V2-ORD-02B` slice.

`AI-LIST-02B` and `V2-ORD-02C` both touch Angular and gateway navigation, so
they are serialized. Shared browser ownership is also serialized.

`AI-LIST-02C` touches Product/Agent/Angular and does not overlap a later
Order-only fulfillment-domain slice, but it must not run beside a business
cleanup that includes frontend or gateway files.

No AI slice may activate payment, checkout, fulfillment, moderation, or
authentication behavior.

## 10. Planning References

The plan adopts workflow properties rather than adding a framework dependency:

- OpenAI Agents documents that input/output guardrails apply at chain
  boundaries while tool guardrails protect each custom function-tool call:
  <https://github.com/openai/openai-agents-python/blob/main/docs/guardrails.md>.
- OpenAI Agents tracing records model, tool, guardrail, and handoff spans and
  requires explicit handling when sensitive input/output must be excluded:
  <https://github.com/openai/openai-agents-python/blob/main/docs/tracing.md>.
- LangGraph emphasizes durable execution and human-in-the-loop interruption
  for stateful agents:
  <https://github.com/langchain-ai/langgraph/blob/main/README.md>.

The existing Agent Service already owns strict tools, replay, audit, and
evaluation interfaces. `AI-LIST-02` applies these patterns through those
interfaces; it does not require LangChain or LangGraph installation.
