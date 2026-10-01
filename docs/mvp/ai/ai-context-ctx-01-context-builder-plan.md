# CTX-01 Typed ContextBuilder / ContextPacket foundation plan

Status: **CTX-01 implemented and locally verified on `codex/context-management`; not a deployment claim**. This document retains the pre-implementation design and acceptance criteria below. The [broader context architecture](ai-context-architecture-plan.md) remains a proposal for later milestones.

Related work: `AI-DISC-AGENT-V2-PLAN-01`, `AI-DISC-AGENT-V2-SCOPE-GATE-01`, `AI-DISC-AGENT-V2-SELLER-CONTEXT-03`, `AI-SKILL-CLI-00`, `AI-CONF-01`, and CTX-00. CTX-01 changes internal agent code and tests, but no public API, database schema, migration, frontend, or provider request contract.

## Implementation record (2026-09-29)

The service now builds one actor/session-owned `MarketplaceTurnContext` from the CTX-00 prior-row read and session state. The orchestrator derives a fresh `MarketplaceContextPacket` for each model decision, projects it into the unchanged `AgentContext`, and passes the same ordered tool schemas separately. The builder owns selection and projection only; safety, scope, confirmation, tool policy, and Skill authorization remain with their previous owners. Direct orchestrator callers use an internal adapter to the same decision builder. `ContextTrace` is an ephemeral in-process test/debug value, not a logged or persisted artifact.

New tests cover the empty and 100-row windows, actor/session rejection, out-of-scope-pair exclusion, latest ordered listing cards, restored observations, legacy `AgentContext` JSON parity, a provider request parity fixture, and second-decision Skill/search suppression. Existing V2 API/SSE, ReAct, Skill, provider, service, and Stop/Retry regressions remain green: `401` V2 tests passed with `3` environment skips; the full agent-service suite passed `1041` tests with `46` environment skips; `11` real MySQL persistence integration tests passed. `git diff --check` passed. No migration was added. CTX-00's 100-row bound and lack of durable same-session turn serialization remain known limitations.

## 1. Executive summary

CTX-01 should extract the *existing* Marketplace Agent V2 context selection into one typed, in-process boundary. A pure `ContextBuilder` will assemble the actor-owned turn snapshot from already-authorized rows and current session state, then derive one `MarketplaceContextPacket` for each model decision. The packet will produce the existing `AgentContext` shape. The controlled ReAct loop, dynamic tool schemas, provider request format, safety gates, confirmations, and streaming remain unchanged. The builder will not retrieve new sources or infer new durable memory.

The principal risk is prompt drift, not a missing data store. The acceptance gate is equivalence of the model-visible JSON and ordered tools for the same frozen turn and decision sequence, plus existing V2/API regressions.

## 2. Current implementation walkthrough and CTX-00 state

1. `MarketplaceAgentV2Service.send_message` checks the V2 session, calls `begin` to persist the USER row and invocation, handles deduplicated or exhausted requests, and passes the accepted USER message to the caller ([service.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/service.py), around lines 184–230). Response-only Retry re-enters this path with the original client-message identity (around lines 849–880).
2. CTX-00 is present in this worktree: the service calls `list_context_messages(..., before=begin.user_message, limit=100)` ([service.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/service.py), around line 244). The V2 wrapper checks actor/session and USER role; the shared repository selects rows strictly before the current `(created_at, message_id)` in descending SQL order and returns them chronologically ([persistence.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/persistence.py), around line 890; [agent_persistence.py](../../../agent-service/src/msb_agent_service/agent_persistence.py), around line 1571). The separate forward history cursor remains ascending. The >100-row MySQL regression is in [test_agent_persistence_integration.py](../../../agent-service/tests/test_agent_persistence_integration.py), around line 143. CTX-00 does **not** create durable turn serialization or a context revision.
3. The service decodes `pendingInteraction` and `activeWorkflow` from `preference_state_json`, filters the current USER and previous OUT_OF_SCOPE assistant/user pairs, takes the last 12 surviving dialogue messages, finds the most recent result-bearing assistant's ordered listing attachments (up to 20), and restores up to 12 valid persisted V2 observations ([service.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/service.py), around lines 242–255 and 1148–1279). It separately classifies scope and may update/consume/cancel workflow or confirmation state before orchestration. Hard safety, seller-field resolution, confirmation handling, and other terminal branches can complete with zero model decisions.
4. `MarketplaceAgentV2Orchestrator.run` receives those values as separate arguments. It initializes decision observations from the last five persisted observations, computes policy and contextual-refinement state, converts the last 12 dialogue pairs to `{"role", "content"}` dictionaries, loads registry schemas and eligible Skills, then enters the five-decision loop ([orchestrator.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/orchestrator.py), around lines 479–505, 745–841, and 1115–1247). The orchestrator also has early guarded outcomes that do not reach that loop. A `load_skill` decision consumes one step, reveals the full Skill, narrows the executable schema set, and continues; tool/rejection observations append to the current-turn list for later decisions.
5. Each model decision receives a newly constructed `AgentContext` ([schemas.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/schemas.py), around line 930). It contains the current text, up to 12 recent messages, listing IDs/cards, up to five customer-safe observations, effective pending/workflow state, contextual refinement, scope, and compact or active Skill. A current-turn search with attachments suppresses earlier listing cards/IDs in that decision. Purchase-snapshot listing IDs from prior observations may also enter `referencedListingIds`.
6. The provider accepts `AgentContext` plus a **separate ordered tool-schema sequence**. It dumps camel-case JSON with `exclude_none=True`, strips specific cart/order and confirmation control metadata, then sends that compact JSON as one user input with the unchanged system instructions. The Responses request uses `store=False`, `truncation="disabled"`, `max_tool_calls=1` when tools exist, and the current `_required_tool_choice` logic ([provider.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/provider.py), around lines 149–265). The provider does not fetch history. It does perform a final privacy projection and tool-choice calculation, so `AgentContext` is not itself safe to serialize indiscriminately outside that path.
7. Runtime capability gates determine the registry names. `SkillRegistry.available` includes only Skills whose allowed tools are already enabled; compact metadata precedes selection, while `Skill.model_context` supplies full instructions after load ([runtime.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/runtime.py), around lines 135–160; [skill_registry.py](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/skill_registry.py), around lines 26–149). Skills do not authorize tools; `MarketplaceAgentV2ToolPolicy` and the owning Java services remain authoritative.

The current API contract retains V2 session/history/send/stream/Stop/Retry routes and unchanged SSE semantics ([api-contract.md](../api-contract.md), V2 section). The legacy discovery lane is separate.

## 3. Current context assembly call graph

```text
V2 service: owned session + accepted USER/invocation
  ├─ CTX-00 latest 100 rows before current USER
  ├─ service helpers: dialogue/OOS filtering, latest cards, observations
  ├─ session JSON: pending interaction and seller workflow
  └─ scope and confirmation/workflow transitions
        ↓ separate arguments
V2 orchestrator: 12-message/5-observation windows, policy inputs,
                 contextual refinement, Skills, dynamic tool schemas
        ↓ AgentContext + ordered schemas per model decision
Provider: final redaction + JSON serialization + system instructions
        ↓ stored-disabled, truncation-disabled Responses decision
```

There are three distinct bounds today: 100 persisted rows at CTX-00, 12 dialogue messages and 12 restored observations at the service boundary, and five model-visible observations in the orchestrator. The provider's `AgentContext` schema independently caps 12 messages, 20 listing references/cards, five observations, and 32 compact Skills. These bounds must not be silently increased or applied in a different order.

## 4. Problem CTX-01 solves

The service, orchestrator, and provider each transform context, so future additions could bypass out-of-scope filtering, accidentally expose backend-only state, or alter which observation/listing/Skill reaches a decision. Today there is no single typed object that records the selected sources, current-turn evolution, and sanitized selection metadata. CTX-01 creates that boundary and parity tests; it does not claim improved long-term recall, lower token use, or serialized turns.

## 5. Goals

- One named, testable owner for existing turn-source selection and per-decision context assembly.
- Explicit actor/session/current-USER identity and source cutoff in backend-only packet metadata.
- Preserve the existing `AgentContext` provider representation and every selection/bounding order.
- A small ephemeral trace that explains source selection without content or secrets.
- A versioned in-process packet that can evolve in later approved CTX slices without a public contract change.

## 6. Non-goals and safety boundaries

The builder does not authenticate, authorize, classify scope, decide tools, interpret customer intent, validate tool arguments, execute tools, consume confirmations, update the seller workflow, obtain Java-service facts, or control ReAct steps. Those remain in the existing service/orchestrator/policy/registry/provider/owning-service boundaries. It must not turn context evidence into authorization or current commerce truth. Existing early no-model outcomes remain early no-model outcomes.

CTX-01 adds no Redis, OpenSearch memory, embeddings, memory extraction/promotion, episode compaction, active-goal or preference persistence, durable result-set registry, token allocator, summarization, new RAG corpus, Kafka scheduling, turn queue/fence/lease, provider storage, or major prompt redesign.

## 7. Proposed ContextBuilder responsibility and location

Add `agent-service/src/msb_agent_service/marketplace_agent_v2/context_builder.py`. It belongs beside V2 service/orchestrator because the selection rules (V2 scope actions, listing attachment DTO, safe observations, Skill DTOs) are V2-specific; the shared persistence repository and legacy discovery path should not depend on it. Keep the module pure and synchronous for CTX-01: it accepts already-owned `MarketplaceAgentV2Session`, accepted USER identity, and `AgentMessage` rows, rather than opening a database connection itself. This preserves the current persistence authorization and avoids repeating `get`, whose confirmation-expiry read can have a durable effect.

The builder should own:

- the exact `_marketplace_context_messages`, `_referenced_listings`, `_recent_observations`, pending/workflow decode, and count/order rules now spread through `service.py`;
- construction of an immutable turn-source snapshot before scope/workflow handling;
- a copy-with-updated-effective-state operation after the service's existing confirmation/seller/scope transitions;
- per-decision `AgentContext` construction from that snapshot plus orchestrator-owned current-turn observations, active Skill and policy-derived refinement;
- source selection metadata and an ephemeral `ContextTrace`.

The builder should **not** move `_contextual_refinement_selection`, `_commerce_listing_ids`, dynamic schema filtering, `_required_tool_choice`, or confirmation/return/cart policy in the first extraction unless an exact parity test proves an isolated move. These are current behavior/policy calculations, not generic retrieval. The builder can accept their already-computed values and reproduce the current `AgentContext` fields. `_customer_observation` may move with the model-context projection only after its search-result redaction has a golden test. Service persistence of observations via `_persistable_observation` remains separate from model-visible selection.

## 8. Proposed typed packet and trust classification

Use frozen dataclasses or equivalently strict immutable models. The names below are proposed internal Python types, not wire schemas or MySQL entities:

```python
ContextIdentity(
    actor_user_id: str, session_id: str, invocation_id: str,
    current_user_message_id: str, before: MessageCursor,
)

MarketplaceTurnContext(
    identity: ContextIdentity,
    current_message: str,
    source_message_ids: tuple[str, ...],
    recent_messages: tuple[tuple[str, str], ...],  # selected, chronological
    referenced_listings: tuple[ListingAttachment, ...],
    result_source_message_id: str | None,
    prior_observations: tuple[ToolObservation, ...],  # selected last 12
    pending_interaction: MarketplaceAgentV2PendingInteraction | None,
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None,
)

MarketplaceContextPacket(
    schema_version: Literal["ctx-01-v1"],
    turn: MarketplaceTurnContext,
    scope_result: MarketplaceScopeResult,
    effective_pending_interaction: MarketplaceAgentV2PendingInteraction | None,
    effective_active_workflow: MarketplaceAgentV2ActiveWorkflow | None,
    decision_observations: tuple[ToolObservation, ...],  # current window, max 5
    contextual_refinement: MarketplaceAgentV2ContextualRefinement | None,
    available_skills: tuple[SkillSummary, ...],
    active_skill: ActiveSkill | None,
    suppress_prior_listings: bool,
    trace: ContextTrace,
)
```

`MarketplaceTurnContext` is assembled once from the same session snapshot and CTX-00 rows the service already reads. Its source IDs and cutoff are backend-only; the current message, selected dialogue, listing cards, and safe observations may become model-visible only through the existing projection. `result_source_message_id` is the latest result-bearing **assistant message ID**, not a new durable result-set ID. The effective pending/workflow fields in the decision packet reflect service/orchestrator transitions during this turn; they are not newly persisted facts. The decision observations include the same last-five window after current-turn tool or rejection observations. Skill metadata is supplied by the registry/orchestrator, not discovered by the builder. Tool schemas stay a separate policy/registry-owned argument, not a field that the packet can authorize.

The packet is backend-only. Its trace and identity must never be part of provider JSON, SSE, public history, or prompt text. It is per decision and ephemeral; no new persistence table or session JSON field is needed. Avoid speculative goal, fact, memory, token, or revision fields in CTX-01.

## 9. Relationship to `AgentContext` and provider-safe projection

Choose **C**: retain `AgentContext` as the current provider-facing DTO, with `MarketplaceContextPacket` as the richer internal selection artifact. The packet has one `to_agent_context()` or builder projection. That projection must reproduce the exact current `AgentContext` constructor: last-12 role/content dictionaries; current message separately; current-search suppression of prior listings/IDs; purchase-snapshot listing IDs from prior observations; last-five `_customer_observation` results; effective pending/workflow; contextual refinement; scope; compact Skills before load or one full active Skill after load. The packet must not retain a second mutable copy of these values.

The provider should continue receiving `AgentContext` and the existing ordered schemas, **not** the whole packet. Its existing final allowlist/redaction (`model_dump(mode="json", by_alias=True, exclude_none=True)`, cart/order control-field removal, confirmation summary projection) remains the provider-safe JSON boundary. Initially keep that implementation and `_required_tool_choice` in `provider.py`; extracting a pure serializer later is acceptable only with exact request fixtures. System instructions remain in the `instructions` field, dynamic JSON remains one user `input`, Skill content remains an `AgentContext` field, and tools remain the separate `tools` array. `store=False`, `truncation="disabled"`, `max_tool_calls`, timeout, reasoning setting, and provider stream parsing do not change.

## 10. Per-turn and per-decision lifecycle

```text
Committed V2 session + accepted USER/invocation + CTX-00 prior rows
        ↓ ContextBuilder.build_turn (once; existing source selection)
MarketplaceTurnContext
        ↓ service's unchanged safety/scope/confirmation/workflow handling
effective turn state
        ↓ orchestrator's unchanged policy, registry and five-step loop
ContextBuilder.for_decision (step 1) → packet → AgentContext → model
        ↓ optional load_skill OR validated tool/rejection observation
ContextBuilder.for_decision (step 2) → new packet → AgentContext → model
        ↓ up to the existing five decisions
```

The accepted current USER row is a cutoff, never a recent-message duplicate; its body remains the distinct `currentMessage` field. The prior history, selected result-bearing set, and restored observations are stable for that turn. The service's effective pending/workflow and scope may change before the first model decision. Within the loop, current-turn observations, a selected full Skill, policy-driven contextual refinement, current-search suppression, and available tool schemas may change. Build a fresh decision packet from the immutable turn snapshot and current loop state on every model call; do not re-query MySQL or append an entire new transcript after every tool. A Skill load consumes one of the five decisions, makes `availableSkills` empty, sets `activeSkill`, and narrows schemas via existing policy. A tool or rejected proposal appends to the same observation sequence used for the next decision. The builder does not increment steps, select tools, or mutate policy.

Early hard-safety, out-of-scope, seller-field, confirmation, and other guarded outcomes should not be forced through a model packet solely for uniformity. They may still use the turn snapshot for existing scope/context checks; trace `model_decision_count=0` only if a packet/trace is actually built. Do not claim all accepted turns reach a provider.

## 11. Skill integration

`SkillRegistry.available(surface="customer", enabled_tools=registry.names)` stays with the orchestrator/runtime. Before load, pass its existing sorted compact summaries to the builder and include `load_skill` in the decision schemas exactly as today. After a valid model selection, the orchestrator validates membership, sets `policy.skill_allowed_tools`, gives the builder `ActiveSkill.model_context()` for the next packet, and restricts schemas to that Skill's already-enabled tools. The builder neither parses Skill files nor treats their text as permissions. Full Skill instructions are model-visible only through the existing `activeSkill` representation; an unknown or disabled Skill still fails closed. No CLI/library changes belong to CTX-01.

## 12. Sanitized `ContextTrace`

Use a frozen in-process trace generated with each packet. Recommended fields: schema version; current USER ID and cutoff pair; count and ordered IDs of rows considered/selected; count of dialogue rows excluded for `CURRENT_USER` or `OUT_OF_SCOPE_PAIR`; result-source assistant ID and count of cards selected; observation considered/selected counts and opaque source message IDs; booleans for pending/workflow inclusion; scope category/grounding label; eligible Skill names and active Skill name/version; exposed tool names or a schema digest supplied by the orchestrator; and bounded selection-reason enums such as `LATEST_100`, `LAST_12_DIALOGUE`, `LATEST_RESULT_BEARING`, `LAST_5_OBSERVATIONS`, `CURRENT_SEARCH_SUPERSEDES_PRIOR_LISTINGS`, and `SKILL_LOADED`.

CTX-01 keeps this trace **ephemeral in memory**, available to tests and a guarded local debugger only. Do not persist it, emit it to ordinary logs/metrics/SSE, attach it to `AgentContext`, or log raw source IDs at production log level. Actor/session IDs are necessary to validate packet ownership internally but need not be copied into trace output. Do not include message bodies, full prompts, Skill instructions, raw tool arguments/results, private commerce snapshots, payment data, contact data, secrets, or chain-of-thought. CTX-OBS may later define controlled trace retention and redaction after its own privacy review.

## 13. Before/after ownership

```text
Before: Service helpers → separate orchestrator arguments → inline AgentContext
        → provider final redaction/JSON → Responses

CTX-01: Service-owned reads/state transitions → pure ContextBuilder
        → typed turn snapshot → per-decision MarketplaceContextPacket
        → unchanged AgentContext projection + ordered registry schemas
        → unchanged provider redaction/JSON → Responses
```

Persistence still authorizes actor/session reads; the service still owns invocation, Stop/Retry, and durable confirmation/workflow changes; the orchestrator still owns ReAct, policy, Skills, tools, and SSE callbacks; the provider still owns its final safe request projection; Product/Order/Payment/Inventory Java services remain current commerce authority.

## 14. Expected file-by-file implementation changes (later task)

| File | Planned CTX-01 change |
| --- | --- |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/context_builder.py` | New pure typed turn snapshot, per-decision packet, exact selection/projection helpers, ephemeral trace. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/service.py` | Delegate current context-helper selection to builder; pass snapshot/effective state to orchestrator. Preserve begin, safety, scope, confirmations, persistence, Stop/Retry, callbacks. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/orchestrator.py` | Replace inline `AgentContext` assembly with per-decision builder projection; retain policy, registry schemas, Skill validation, five-step loop and output processing. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/schemas.py` | Keep `AgentContext` and its aliases/bounds stable. Change only internal type imports if truly needed; do not create a second wire schema. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/provider.py` | Ideally no functional change. If a serializer helper is extracted for testability, prove identical JSON/request fields and keep final redaction here. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/skill_registry.py`, `policy.py`, `tools.py`, `runtime.py` | No expected behavior edits. Use their existing outputs as builder inputs; narrow test adjustments only if internal signatures change. |
| `agent-service/src/msb_agent_service/marketplace_agent_v2/persistence.py`, `agent_persistence.py` | No query/schema change expected. Reuse CTX-00 latest-context read. |
| `agent-service/tests/test_marketplace_agent_v2_service.py`, `test_marketplace_agent_v2_orchestrator.py`, `test_marketplace_agent_v2_provider.py`, `test_marketplace_agent_v2_skills.py`, `test_marketplace_agent_v2_contract.py` | Add packet/parity/golden and existing behavior regressions; update only internal test seams needed by extraction. |
| `agent-service/tests/test_agent_persistence_integration.py` | Retain and rerun CTX-00 >100-row MySQL regression; do not change its history contract. |
| `docs/mvp/ai/ai-context-architecture-plan.md`, this plan, `docs/mvp/development-roadmap.md` | Later mark CTX-01's actual status, ownership, lifecycle, verified evidence, and deferrals; do not mark future CTX features complete. |

No frontend, gateway, public API, migration, new shared module, or legacy discovery file is expected to change.

## 15. Incremental implementation sequence and rollout

1. Freeze representative existing provider requests and dynamic tool arrays in tests *before* moving assembly code. Cover an ordinary direct answer, search with cards/facets, follow-up, pending confirmation, seller field, out-of-scope detour, Skill load, and a second decision after a tool/rejection observation. Freeze clocks and input order.
2. Add packet/trace types and pure builder tests. Initially compare the builder's output to the existing service/orchestrator helpers in test code, without a second production execution path.
3. Switch only service helper selection to the builder; keep the orchestrator call shape temporarily. Prove actor/session cutoff, last-12 dialogue, latest ordered cards, last-12 restored observations and workflow decode.
4. Pass the typed turn snapshot through the orchestrator. At each model decision, ask the builder for a packet and its `AgentContext` projection; keep policy and schema-selection branches in their original order. Remove superseded inline assembly only after parity is green.
5. Add the ephemeral trace, with serialization/logging guards. Keep provider final sanitization unchanged unless exact fixtures justify a mechanical helper extraction.
6. Run focused packet, provider, Skill, ReAct, service, API/SSE, CTX-00 MySQL, and full V2 regressions. Review representative request diffs and source-level isolation. Update the roadmap only with evidenced results.

Because this is an internal, parity-only refactor, no permanent feature flag is proposed. A short-lived test-only old/new comparison is useful; a production shadow path would duplicate context assembly and risk logging private material. Rollback is a single code revert to the previous service/orchestrator assembly, with CTX-00's latest-read method retained.

## 16. Prompt-drift controls

Exact provider-facing checks should cover `AgentContext.model_dump(mode="json", by_alias=True, exclude_none=True)` **and** the final compact JSON string after provider redaction. Preserve Pydantic field order, camel-case aliases, omission of nulls, `json.dumps` separators and `ensure_ascii=False`, dialogue order, observation order, result-card order, Skill summary/full content and order, and dynamic tool-schema order. Do not add packet schema version, source IDs, trace, or actor identifiers to the prompt. Preserve the existing system-prompt string and request fields (`instructions`, `input`, `tools`, `tool_choice`, `max_tool_calls`, `store`, `truncation`, timeout, reasoning setting).

Use exact string/structural golden assertions for deterministic payloads and ordered schemas. Use semantic assertions for internal packet types, trace counts/reasons, and observations with deliberately variable timestamps/IDs; freeze or normalize only those generated values in request fixtures. Do not normalize away changed null/missing fields, ordering, tool availability, or privacy redaction. If an existing defect is found, record it separately and do not fold its fix into CTX-01.

## 17. Concrete CTX-01 test matrix

| Layer | Required cases and assertion |
| --- | --- |
| Turn packet units | Current USER is separate; zero/ordinary/100-row history; last-12 chronological dialogue; latest result-bearing assistant set or none; exact card order; last-12 valid persisted observations; malformed actions ignored exactly as today; pending/workflow decode; scope and prior out-of-scope pair exclusion. |
| Per-decision packet units | No Skill, compact eligible Skills, active full Skill; first/next decision after tool and rejected proposal; last-five customer-safe observations; current-search suppression of old cards; contextual refinement and effective pending/workflow values; no backend IDs or trace in `AgentContext`. |
| Old/new parity | For frozen session/message/registry fixtures, compare legacy helpers/inline construction with builder selection and `AgentContext`; compare exact provider JSON and ordered schemas across direct answer, discovery/follow-up, seller workflow, confirmation, help, scope detour, Skill load and tool-result second decision. Keep dual construction in tests only. |
| Skills and policy | Compact catalog before load; full selected Skill after load; load consumes one of five decisions; tool schemas narrow; disabled/unknown Skill fails; policy and durable confirmation still override Skill text. |
| ReAct and provider | Five-decision ceiling; one proposal per decision; next-decision observation; same `tool_choice` behavior; no sixth call; `store=False`, `truncation="disabled"`, max-tool-call and final-redaction fixtures. |
| Isolation and long session | Actor A/session A cannot select B context; cutoff excludes current and later USER; rerun CTX-00 >100-row MySQL test with latest cards/observations and unchanged forward history; no cross-actor trace content. |
| API/SSE/Retry | `message_started`, activity/tool completion, text, attachments/citations, done and error paths stay ordered; Stop and response-only Retry reuse the accepted USER and do not duplicate history; refreshed history matches persisted final text. |

Existing anchors: [service tests](../../../agent-service/tests/test_marketplace_agent_v2_service.py) cover latest cards, observations, out-of-scope pairs, pending/seller state and Retry; [orchestrator tests](../../../agent-service/tests/test_marketplace_agent_v2_orchestrator.py) cover decision/observation behavior; [Skill tests](../../../agent-service/tests/test_marketplace_agent_v2_skills.py) cover load/narrowing/policy; [provider tests](../../../agent-service/tests/test_marketplace_agent_v2_provider.py) cover request flags, redaction and tool choice; [contract tests](../../../agent-service/tests/test_marketplace_agent_v2_contract.py) cover SSE shape; the [CTX-00 MySQL test](../../../agent-service/tests/test_agent_persistence_integration.py) covers >100 rows. Add golden request fixtures because existing tests mostly assert selected fields rather than the complete serialized request.

## 18. Acceptance criteria

- Every model call receives one typed per-decision packet, projected into the unchanged `AgentContext` and ordered schemas; no packet metadata enters the prompt.
- Frozen before/after requests match exactly for deterministic fields and JSON; any intentional exception has a separate approved scope.
- Current-message treatment, last-12/last-five bounds, listing set/observations, pending/workflow, scope/out-of-scope exclusion, Skill exposure and allowed tools match current behavior.
- The five-step limit, provider flags, tool policy, confirmation authority, SSE/Stop/Retry, and actor/session isolation pass their existing and added tests.
- CTX-00's >100-row MySQL regression still passes. No new database/API/frontend/legacy-discovery behavior is introduced.
- Trace remains ephemeral, redacted, and absent from provider requests, ordinary logs, durable rows, history and SSE.

## 19. Rollback strategy

Keep CTX-00's latest-context persistence method and regression. If CTX-01 parity or privacy checks fail, revert the builder wiring and packet module together to the prior service/orchestrator `AgentContext` path. Do not change public endpoints or deploy a migration; the existing V2 default-off and kill-switch gates remain available. No stored packet format requires migration or backfill.

## 20. Risks and separate findings

- Provider JSON can drift through alias, null omission, ordering, Skill serialization or changed final redaction even when typed fields appear equivalent. Golden exact requests are mandatory.
- A builder that calls `persistence.get` again may expire a confirmation or see a different session state. CTX-01 should use the service's already-read snapshot and only accept explicit effective-state updates.
- Scope/confirmation/seller and other early branches do not all call the model; forcing them into a uniform model path would be a behavior change.
- The current provider `_required_tool_choice` and orchestrator's contextual-refinement/commerce helpers contain policy-like rules. CTX-01 must not silently relocate or reinterpret them.
- CTX-00 still caps retrieval at 100 prior rows; an old result set outside that window is not a CTX-01 recall feature. Distinct same-session turns are not durably serialized, so CTX-01 must not claim a race-free context revision. These are later-slice concerns.
- The broader architecture document's current-state/oldest-page defect paragraph predates CTX-00; implementation documentation should reconcile that status without treating the rest of its proposed CTX capabilities as shipped.

## 21. Open questions for implementation review

1. Should the packet and turn snapshot be frozen dataclasses or strict Pydantic models? Prefer frozen dataclasses internally, retaining `AgentContext` validation at the provider projection; decide with benchmark and test ergonomics, not wire compatibility.
2. Should the trace carry raw source message IDs only in ephemeral test/debug objects, or hashed IDs even there? Neither option should emit IDs to production logs in CTX-01.
3. Should the builder's per-decision API accept a small typed `DecisionState` from the orchestrator or named inputs? Choose the form that keeps policy calculations visibly outside the builder and golden fixtures simple.
4. Do any existing test fixtures depend on importing the private helper names from `service.py`? Preserve compatibility during extraction, then update tests without retaining permanent duplicate production logic.

These questions do not authorize extra CTX-01 behavior.

## 22. Explicitly deferred CTX milestones

`CTX-02` owns deterministic token budgeting. `CTX-03` owns durable ordered turns, claim/fence and context revision before new durable context writers. `CTX-04` owns active goal and structured fact persistence; `CTX-05` owns durable entity/result references. `CTX-06` may introduce optional Redis only after measurement and durable fallback. `CTX-07/08/09` own private semantic/episodic retrieval and selection. `CTX-10` may coordinate approved help/RAG/Skills under the later budget; `CTX-OBS` owns persisted or operator-visible trace policy. None are part of CTX-01 planning or implementation.
