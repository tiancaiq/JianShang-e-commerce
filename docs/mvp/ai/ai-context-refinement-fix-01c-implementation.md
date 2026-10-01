# REFINE-FIX-01C — guarded terminal recovery

Status: implemented locally with focused live acceptance GO on 2026-09-30. This is an independent refinement slice under `AI-CONTEXT-REFINEMENT-FIX-01`, not CTX-02 or a CTX-01-RC rollout approval.

## Boundary and behavior

For a turn that depends on the latest unexpired, actor/session-scoped executed Product search, provider text is held until the final grounding and presentation validators accept it. A rejected permission question, unsupported current-result claim, or missing current listing grounding produces at most one category-specific `DIRECT_RESPONSE/REJECTED` observation inside the existing five-decision loop. On the next decision, the model may propose `search_listings` with its own arguments or ask a focused clarification. Only that read-only tool is exposed for this correction; existing policy still checks it and a turn cannot execute a second search. Repeated invalid prose or exhausted budget uses the existing guarded terminal failure. Privacy and internal-enum refusals do not enter the 01C repair path. Unrelated direct answers retain streaming.

This changes no public API, SSE event type, Product contract, database schema, or migration. The added observation reasons are private Agent context. No generic provider/parser retry, mutation replay, new service, or durable memory was added.

## Verification

- Deterministic 01C tests cover buffered invalid result/permission text, one model-chosen search or clarification, one repair only, five-decision ceiling, internal-enum refusal, and open tool choice for clarification.
- Service tests cover exact-once final persistence after successful recovery and guarded PARTIAL history without leaked text on repeated failure.
- Full Agent suite: **1,076 run, 47 skipped, 0 failed**. Real MySQL persistence suite: **12 passed**. JavaScript acceptance harness syntax and `git diff --check` passed.
- Live provider + Product acceptance used an isolated worktree Agent and fresh sessions for two repetitions each of laptop → under $1000 → 16GB RAM, laptop under $1000 → under $1500, keyboard → wireless → under $100 → no RGB, and monitor → 27 inch → under $300 → 32 inch. **All 8 runs and 26 turns passed** the focused harness checks: one executed search per turn, intended final query/filter state, no permission or pending-confirmation loop, at most five decisions, terminal `done`, and exactly one persisted user and assistant. Two disposable buyer accounts were disabled after the run. No 01C-specific rejection was forced in the live model; the buffered rejection/recovery branches were exercised deterministically.

Authenticated browser refresh remains to be run before the broader CTX-01-RC sign-off, which remains **NO-GO**. Product relevance and exact query-attribute matching remain separately constrained by Product search and the existing 01B/01B.1 gates. The live harness records safe tool/status/filter/count metadata; it does not prove the model selected a particular Skill on every turn or Product top-K relevance for empty result sets.
