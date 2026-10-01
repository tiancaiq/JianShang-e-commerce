# CTX-01-RC runtime acceptance — 2026-09-29

## Executive verdict

**NO-GO for final release sign-off; CTX-01 context-path acceptance is substantially positive but incomplete.** Real-provider, real-service API acceptance passed discovery, ordinal references, a >100-message session, actor isolation, cart mutations, single-use confirmation, Stop, and response-only Retry. The three-turn refinement case did not complete reliably, and authenticated browser acceptance plus a nonempty-order follow-up were not performed. No P0 or P1 defect was proven to have been introduced by CTX-01; the failed refinement also reproduced on a non-identical, pre-CTX-01 control container. That is evidence against, but not proof of, CTX-01 attribution. Do not present this as production deployment acceptance or begin CTX-02 based on this verdict.

## Scope and environment

Requirement/roadmap IDs: `CTX-00`, `CTX-01`, `CTX-01-RC`; related existing contracts `AI-DISC-AGENT-V2-PLAN-01`, `AI-SKILL-CLI-00`, `AI-CONF-01`. No approved public API or data contract changed in this verification slice.

- Inspected worktree: branch `codex/context-management`; baseline HEAD `54cce3c642994a82e38fca7129b445d09fca32c4`. CTX-00/CTX-01 implementation and plan files were already uncommitted; this run did not modify them.
- Shared demo Docker stack: MySQL, Redis, OpenSearch, Agent, gateway, Auth/Keycloak, Product, Order/Cart, other commerce services, and Angular frontend. The existing Agent container belongs to a different worktree and lacks CTX-01, so it was **not rebuilt or restarted**. Gateway and frontend still point at that container.
- CTX-01 runtime: an isolated host Agent process on loopback port `18086`, using this worktree's Python source and the existing local virtual environment. It inherited the demo Agent configuration in process memory, with local service-address remapping and `NO_PROXY=127.0.0.1,localhost` for Windows HTTPX. `/health` and `/ready` returned 200; readiness reported provider configured, knowledge READY, and persistence READY. This process was stopped after verification. No environment file was edited.
- Provider: **live OpenAI**, `gpt-5-mini`, not a fake/stub. Credential presence was checked without printing its value. The pre-CTX control was the separate running Docker Agent on port `8086`; it is not a byte-identical baseline, so comparison is directional only.
- Agent flags: V2 API/provider/Product tools, commerce reads, cart mutations, checkout, order mutations, return requests, help knowledge, and persistence enabled; V2 kill switch off; V2 hybrid retrieval and query embedding disabled. The gateway's Agent URL still targeted the shared container. The local frontend was reachable but logged out. No mock-payment or checkout mutation was used.
- Fixtures: each harness invocation created two unique buyer-only local Keycloak users with random in-memory credentials; all created users were disabled in `finally` (two of two per run). Only new fixture accounts were touched. Agent-owned acceptance sessions/messages and the long-session SQL fixture remain in local demo MySQL. The cart sequence ended with its dedicated verified-business item removed. No pre-existing user password or production data was altered.

The local-only reproducible harness is `tools/ctx01-rc-runtime-acceptance.mjs`. Its `--long-only`, `--cart-only`, `--confirmation-only`, and `--stop-retry-only` modes isolate destructive-risk surfaces. It prints safe event, result, and count summaries; it never prints tokens/passwords or raw provider traffic. Live evidence run IDs: `mullxz34` (main), `mulltoet` (long), `mulm1wur` (cart), `mulmck44` (confirmation), `mulmbi7p` (Stop/Retry), and `mullv3n3` (directional control).

## Scenario results

| Scenario | Result | Evidence and boundary |
| --- | --- | --- |
| A — basic discovery | PASS | Live V2 SSE: `message_started`, activity, `search_listings` `SUCCEEDED/RESULTS_AVAILABLE`, text, five attachments, `done`; one USER and one ASSISTANT persisted; two model decisions. No clarification loop. |
| B — second listing | PASS | Same session selected the exact second card (Arsor one-handed keyboard); `get_listing` succeeded and one matching card persisted. This proves current result-set reference, not a durable entity registry. |
| C — three-turn refinement | **FAIL / attribution open** | Laptop search and price refinement executed in one run; the RAM refinement emitted `MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED`, with one retryable USER and no completed assistant. Another run failed on price or asked for repeated permission. Service log classified `MODEL_RESPONSE_UNSUPPORTED`. The old, non-identical control also produced this failure on price refinement and repeated permission. No wrong commerce mutation or actor leak was seen. |
| D — tool observation then next decision | PASS at behavioral boundary | Search produced a safe `RESULTS_AVAILABLE` observation and a final answer in two decisions; the completed card set matched returned results. Unit parity tests prove the second-decision packet is rebuilt with current observation, not the first packet. No live packet contents were logged. |
| E — Skill disclosure | AUTOMATED ONLY | Live activity does not expose internal `load_skill`, so Skill selection cannot be asserted from customer SSE. Existing tests cover compact-before-load, active instructions/allowed-tool narrowing after load, unknown Skill rejection, and five-decision accounting. Discovery and cart behavior succeeded live, but their exact Skill selections were not directly observed. |
| F — cart pronouns | PASS | On a Product-verified BUSINESS fixture, search displayed its card; add, quantity two, and remove each succeeded. Authoritative Cart Service showed one item/quantity one, then one/quantity two, then zero. Later duplicate proposals were rejected as `DUPLICATE_TOOL_CALL`; no duplicate mutation occurred. |
| G — order context | PARTIAL | `Show my orders` called `list_my_orders` and correctly reported `NO_ORDERS`; authoritative Order API returned zero. No safe nonempty owned order was available for the “latest one” follow-up, so that portion is untested. |
| H — durable confirmation | PASS | A typed pre-search approval produced a `CONFIRM_ACTION` pending state; first `Yes` executed one search with zero model decisions; repeated `Yes` executed no tool and reported already completed. Agent MySQL had exactly one `CONSUMED` confirmation with `consumed_at` set. |
| I — out-of-scope detour | PASS | In one clean session: mouse search, capital-of-France out-of-scope response with zero decisions/no tool, then “Back to the mouse—second one” selected the prior second mouse-result card. A preliminary fixture had inserted a one-card detail between search and detour, changing the latest presented set; it was corrected and is not counted as a defect. |
| J — >100 messages / CTX-00 | PASS | An actor-owned 113-row fixture placed the two-card result after row 100 and different future-dated rows after it. The real turn selected the exact second prior card, not the future card; Agent MySQL showed one invocation and one assistant. Public forward history still began at the oldest filler row with `hasMore=true`. This long turn used the prior card without a fresh `get_listing`; scenario B separately revalidated via Product. |
| K — actor isolation | PASS for data isolation; status defect | A and B had distinct sessions. B's read of A's session returned HTTP 500 with no history; B's stream against A's session emitted only an error, with no accepted message or answer. No A context was exposed. The old control gave the same 500, so the status mapping appears pre-existing. Browser cross-actor test was not performed. |
| L — SSE/browser | API PASS; browser SKIPPED | Representative API SSE event order and persisted answer/card counts matched. Failed refinement emitted error, not false `done`. Angular was reachable but logged out and wired to the non-CTX container; no authenticated UI refresh, loading-state, or card-render check can be claimed. |
| M — Stop | PASS | Stop after `message_started` returned `STOPPED`/HTTP 200; one committed USER and a retryable guarded assistant remained. The same session handled a subsequent second-card turn after Retry. |
| N — response-only Retry | PASS | Retry used the original user ID and client-message ID, completed SSE, left exactly one USER and one final ASSISTANT, and cleared retryability. |
| O — early no-model path | PASS for representative branches | Out-of-scope turn and confirmed search each used zero provider decisions. Seller-field and hard-safety early branches were not exercised live; automated regressions cover them. |

All completed live turns reported at most five model decisions. The one-tool-proposal-per-decision contract and policy gates remain covered by the provider/orchestrator suites; customer SSE does not expose raw model proposals. There was no unauthorized order, checkout, payment, or return action.

## Context, provider, and authoritative-state observations

The live run used `MarketplaceAgentV2Service` from this worktree, which builds the turn from the CTX-00 prior-row read and passes the per-decision builder into the orchestrator. The >100 fixture and current/future cutoff result are behavioral evidence for that path. The pure ContextBuilder tests assert recent order, last 12 prior observations, latest result source, ordered cards, actor/session rejection, out-of-scope filtering, last-five decision observations, current-search suppression, and compact/active Skill state. A frozen provider test asserts exact request parity. The trace is intentionally ephemeral; no backend packet trace was logged or directly inspected during live requests. Therefore precise live packet source IDs/counts and wire-level omission of trace metadata are **not independently observed**.

Source and automated tests show that the provider projects `AgentContext`, omits trace/schema/source identity from the model-visible JSON, and sends Responses with `store=False`, `truncation="disabled"`, and `max_tool_calls=1` when tools exist. Live calls reached that provider and returned actual model decisions, but raw provider payloads were not captured for privacy. Product was authoritative for search/detail and the verified business fixture; Cart and Order APIs, not assistant prose, supplied the commerce cross-checks. Confirmation state was checked directly in Agent MySQL.

## Findings and severity

1. **P1 acceptance blocker, CTX-01 attribution unproven:** intermittent `MODEL_RESPONSE_UNSUPPORTED` / interrupted SSE during consecutive laptop refinements prevents a clean full scenario C pass. Expected: each constraint applies without a redundant permission request and completes once. Actual: a retryable failed turn or repeated clarification. The non-identical old container reproduced both classes of behavior, suggesting pre-existing provider/decision behavior; it does not prove CTX-01 harmless. Reproduce and isolate with a controlled equivalent baseline before sign-off. No production fix was made during this verification.
2. **P2, likely pre-existing:** cross-actor session history denies access without disclosure but maps to HTTP 500 instead of an intentional 403/404. Old control reproduced it. Ownership: V2 API error mapping, not context selection.
3. **P2, pre-existing product/retrieval quality:** broad laptop results included bags/batteries/accessories; keyboard results included keycaps; the old control showed similar results. Results-first acceptance should assess relevance separately from CTX-01. The mouse second result was a keyboard/mouse combo, but ordinal selection was correct for the displayed set.
4. **P2 observability/acceptance gap:** live Skill choice and ephemeral `ContextTrace` cannot be inspected through existing customer SSE, and browser acceptance was unavailable without redirecting the shared frontend or an authenticated UI fixture. Neither warrants adding permanent private logging in CTX-01-RC.
5. **P3:** cart turns consumed extra decisions after safe duplicate-tool rejection, without repeating the mutation.

No P0 was observed. No proven CTX-01-introduced P1 was found. The incomplete scenario C and browser/nonempty-order gaps still prevent final GO.

## Regression verification and changes

Rerun from this worktree after the live walkthroughs:

- Marketplace Agent V2-focused unittest discovery: **401 run, 3 skipped, 0 failures**.
- Full agent-service unittest discovery: **1,041 run, 46 skipped, 0 failures**. It emitted expected failure-path test logs and a pre-existing discovery-test coroutine warning; test exit was zero.
- Agent persistence MySQL integration with an isolated MySQL 8.4 Testcontainer and `RUN_MYSQL_INTEGRATION=1`: **11 run, 0 skipped, 0 failures**.
- `git diff --check`: **pass**. Harness syntax: `node --check` pass.

Files added for this acceptance: this document and the local-only harness. `docs/mvp/development-roadmap.md` gains this report reference. No production/runtime code, public API contract, migration, feature flag, environment file, provider configuration, or shared container was changed in CTX-01-RC. Existing uncommitted CTX-00/CTX-01 implementation changes were preserved.

Known deferred architecture remains outside this gate: token-aware budgeting, durable active goals/preferences, same-session serialized turns or fencing, Redis hot context, semantic/episodic memory, and a durable entity/result-set registry. Do not reinterpret their absence as a CTX-01 regression.

**Recommendation:** hold CTX-01-RC sign-off; reproduce the refinement failure with a truly equivalent pre/post-CTX build, then rerun scenario C and authenticated browser acceptance on the CTX-01 runtime. If the failure is confirmed pre-existing, record its separate owner and severity before choosing the release gate. CTX-02 planning can follow that decision; CTX-02 implementation is not part of this task.
