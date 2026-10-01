# CTX-01-RC-CONTEXT-FINAL — human and adversarial context acceptance

Date: 2026-10-02 (Asia/Shanghai). Scope: `CTX-00`, `CTX-01`, `REFINE-FIX-01A/B/B.1/C/D/D.1`. This is a **verification-only, context-only** gate. It does not authorize CTX-02 or a production change.

Historical gate record: the Matrix 3 NO-GO below is preserved as observed. The
subsequent [REFINE-FIX-01E focused implementation](ai-context-refinement-fix-01e-implementation.md)
passed its narrow reruns; the full context-only gate in this document has not
been rerun and its original verdict is not retroactively changed.

## Executive verdict

**CTX-01 CONTEXT NO-GO.** Matrices 1, 2, and 4 passed 5/5 fresh sessions, but required Matrix 3 passed **0/5**. Its bare-title opening message did not execute `search_listings`; the Agent exhausted five decisions and returned a knowledge-document fallback. Because no active search was established, the following explicit replacement was not counted. The matrix is not rescued by the separately successful `Search for ...` baseline or `Search just ...` replacements. The gate's 5/5 condition is unmet. Several natural/weird turns also ended without an executed refinement or with an interrupted stream. No CTX-02 implementation began.

## Runtime and method

- Worktree on branch `codex/context-management`; isolated host Agent loaded this worktree's `agent-service/src` at `127.0.0.1:18086`. Shared local MySQL, Auth/Keycloak, Product, Order/Cart, OpenSearch, Kafka, and live configured provider were used. The shared Agent at `8086`, gateway, and user browser at `4200` were not treated as worktree evidence or changed. The isolated Agent process was stopped after testing.
- `tools/ctx01-rc-runtime-acceptance.mjs` used authenticated disposable buyer-only identities, fresh V2 sessions, SSE terminal/history checks, and a narrow read of **Agent-owned persisted `search_listings` observations**. A refinement counted only when a new successful applied-search observation appeared after that turn. Logged evidence was limited to query, typed condition/price/currency, result IDs/count, tool status, decision count, and user-visible terminal text; no provider reasoning or secrets were logged.
- Initial 20-session matrix run ID `mupx54c1`. Its two fixture accounts outlived the initial admin credential and were subsequently located by exact username/ID and manually disabled. The harness now refreshes the admin credential before cleanup. All later run reports showed `2/2` disabled. Cart mutations used only a Product-verified business fixture in a disposable buyer cart and were reversed. No checkout, payment, or order mutation was attempted.
- This gate used live API/SSE and persisted-session reconstruction, not a new authenticated-browser run. Earlier browser evidence remains in `ai-context-ctx-01-rc-final.md` and is not promoted to a new browser PASS. History reload was exercised; a cold Agent process restart between turns was not.

## Required repeated matrix

| Matrix | Fresh-session result | Newly executed search evidence |
| --- | ---: | --- |
| 1. `monitor → 27 inch → under $300 → actually 32 inch` | **5/5 PASS** | Each final turn executed one search for `32 inch monitor`, `maximumPrice=300`, `currency=USD`; no `27 inch`. Intermediate turns also executed one search each. |
| 2. `monitor under $300 → 27 inch` | **5/5 PASS** | Each final turn executed one search for monitor + `27 inch`, retaining `maximumPrice=300` USD. |
| 3. bare `RC Delivered Return Fixture Harbor Cart Supply → Search just RC Delivered Return Fixture` | **0/5 FAIL** | All five opening turns had **zero** executed searches and five decisions. A focused repeat returned the marketplace-document fallback. The replacement turn was not reached by the strict matrix runner. This is an intent-routing failure of the exact required opener, not proof that a previously executed query retained a stale store term. |
| 4. `Dell monitor under $300 → Search just monitor` | **5/5 PASS** | Each final turn executed one search for exactly `monitor`, with `maximumPrice=300` USD and no `Dell`. |

All counted matrix turns had one committed USER/final ASSISTANT pair, a terminal `done`, no pending search confirmation, and at most five model decisions. At the time of this gate, harness process exit `0` meant it completed and printed checks; it did **not** turn the failing Matrix 3 into a PASS. The maintained harness now exits nonzero when a check fails.

## Human, correction, and replacement probes

| IDs | Observed context-only result |
| --- | --- |
| A1–A4 | Laptop opener executed. In the initial natural run, `something under a grand` did not produce a new search; in a focused repeat that turn was stream-interrupted. A later `nah 1500 is fine` executed laptop with max $1,500, but the $1,000-to-$1,500 supersession was therefore **not proven**. `16 gigs minimum` then executed `laptops 16GB` with max $1,500. `new only` ended after five rejected refinement attempts without a new search. |
| A5 | `white wireless keyboard` executed. `dont care about color anymore` did not execute a color-free search; it said it would remove color and asked whether to search. No false completed-search claim was counted, but this is an unnecessary read-only confirmation. |
| B1 / Matrix 3 | The exact bare-title baseline failed 0/5. With an explicit `Search for ...` baseline in separate probes, the old store-bearing query did execute. |
| B2 | `forget Harbor Cart Supply, just RC Delivered Return Fixture` executed no replacement and fell into a document-answer fallback, not a focused search clarification. It did not silently execute a stale query. |
| B3–B4 | `just red keyboard now` executed `red keyboard`, removed Logitech, retained typed $100. `same filters, just monitor` executed `monitor`, removed Dell, retained typed $300 in the focused repeat. |
| C1 / Matrix 1 | 5/5. Size 32 superseded 27 while $300 survived. |
| C2–C3 | Casual `make it 27 inch` and `below 300` executed correctly; `wait, 32 actually` was stream-interrupted, so final C2 was not verified. Keyboard + wireless kept $100; `no rgb tho` did not execute in the first run. The clear `NO RGB` spelling in J2 did execute correctly. |
| D1–D2 | `scratch that, show me keyboards` executed a new keyboard query without laptop terms and reset the old laptop price. `same budget, show me keyboards` did not execute in the observed run; carry-forward was not proven. |
| I1–I4 | `laptps` executed laptops; `undr 1000` executed laptops with max $1,000; `moniter 27 inch` executed `27 inch monitor`; `search jus RC Delivered Return Fixture` executed the clean title after the explicit store-bearing baseline. |
| J1–J3 | `UNDER $300!!!` applied typed $300; `NO RGB` executed `keyboard no RGB` with prior $100; `actually... 32 inch` replaced 27 with 32. |
| K1–K3 | Repeated `under $300` retained one typed $300; repeated `wireless only` remained `keyboard wireless`, not doubled. `actually 32 inch` after `32 inch` caused no new search in the observed run, but no duplicated size or contradictory executed search appeared. |
| L1–L3 | $500 superseded $300 in a new monitor search. `used is okay actually` executed `monitor used` with condition `GOOD`, replacing `NEW`. `wired is fine too` did not yield a completed new search in one run and was stream-interrupted in a repeat; generalization remains unverified. |
| M1–M4 | `Search just wireless keyboard` executed exactly that query, removing red and Logitech. The filler-heavy explicit omission M2 executed no clean search and returned a document-answer fallback after rejected proposals. Same-message `27 inch—actually 32 inch monitor` executed no search; no contradictory query was executed. Conflicting same-message price M4 was stream-interrupted in the original and repeat, so latest-correction behavior is unverified. |

For all successful refinements above, the cited values came from new persisted applied-search arguments, not assistant prose. Product's result ranking or number of relevant listings was not used to score the context transformation.

## Ordinals, pronouns, detours, and ambiguity

| IDs | Result |
| --- | --- |
| E1–E2 | A five-card mechanical-keyboard search displayed IDs in order. `the second one` and `tell me more about #2` each attached exact second ID `CH9E3CZQ1NRMFTDEV3SCTN3TCT`. |
| E3 | Exact fixture search displayed one card. `the second one` attached no card and did not silently select the first. The observed terminal used all five decisions; focused clarification quality was not established. |
| E4 | `Show me 3 mechanical keyboards` displayed exactly three cards. `the fifth one` received a deterministic current-set out-of-range refusal, zero attachments, zero model decisions. A five-card/sixth-one variant also refused safely. |
| E5 | Five-card mechanical-keyboard search followed by `Show me 2 mechanical keyboards` displayed two current cards. `the third one` received the deterministic current-set refusal, not the older third ID. |
| F1–F2 | A second mouse-pad card resolved to the exact displayed second ID. Subsequent `Add it to my cart` did not mutate the disposable cart in the observed F1 run. After switching to keyboards in F2, `Add it` likewise made no observed mutation or old-pad selection. Positive F1 cart resolution was not proven. |
| F3 safe-fixture variant | The verified business fixture search displayed one card. `Add the first one to my cart` produced authoritative Cart quantity 1; `Make it quantity 2` produced 2; `Remove it` left no fixture item. Duplicate add/remove proposals were rejected as `DUPLICATE_TOOL_CALL`, without duplicate mutation. This proves pronoun quantity/remove on a safe fixture, **not** the exact second-business-card wording. |
| G1–G2 | Exact wireless-mouse search displayed only one card, so the positive second-mouse detour could not be proven; no wrong first-card attachment followed the detour. In G2, keyboard/$100 was recognized in the final prose after two unrelated turns, but `anyway, wireless only` asked permission and executed **no** new search, so the required return-to-refinement is not accepted. |
| G valid-set variant | Five mechanical-keyboard cards, out-of-scope capital question, then `back to the keyboard, second one` attached the exact original second ID. This proves valid ordinal context can survive a detour, but does not rescue G2's missing search. |
| H1–H4 | `make it better` made no search. `cheaper` without a price anchor selected a displayed lowest-price card rather than clarifying; it made no mutation, but the interpretation was overconfident. `the other one` after five cards was stream-interrupted in the original and focused repeat, so safe clarification is **inconclusive**, not a PASS. `same one but better` asked a focused clarification and selected no card. |

## Persistence, long history, and isolation

- **N1–N2 PASS for persisted reconstruction:** after a history reload, `Dell monitor under $300 → Search just monitor` executed `monitor` at max $300, and `monitor under $300 → 27 inch` executed `monitor 27 inch` at max $300. A browser refresh or cold process restart was not repeated in this context-only API run.
- **O PASS:** two actor-owned sessions were seeded with 110 chronological filler messages before a real post-row-100 baseline. Both ended with 114 messages. `monitor under $300 → 27 inch` executed `27 inch monitor` at max $300; `Dell monitor under $300 → Search just monitor` executed `monitor` at max $300 without Dell. The newest context, not the oldest 100-row page, drove the refinements.
- **P PASS for data isolation:** in final run `mupygkao`, A executed `Dell monitor`/max $300, B independently executed `monitor`/no price, and A's other session independently executed `monitor`/no price. B-token access to A's session was denied without message or result disclosure. An earlier fixture ordering produced an interrupted A opener; creating A and sending its first turn before opening the other sessions removed that environmental ambiguity. Cross-owner HTTP status mapping is excluded below.

## Automated validation and change record

| Post-live run | Passed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: |
| Focused CTX-00/persistence, ContextBuilder, 01A/B/C/D, orchestration/policy-adjacent, provider, V2 service, capabilities/Skill, and V2 contract files | 266 (plus 120 subtests) | 0 | 0 | 0 |
| Full `agent-service` suite | 1,043 (plus 450 subtests) | 0 | 47 | 0 |
| Real-MySQL Agent persistence integration | 12 | 0 | 0 | 0 |
| `node --check` acceptance harness and `git diff --check` | pass | 0 | 0 | 0 |

The first focused invocation referenced a nonexistent `test_marketplace_agent_v2_policy.py` path and exited 4 without collecting tests; the corrected 266-test invocation above exited 0. The policy behavior is covered by the existing orchestrator/capability/contract files. `pytest -p no:cacheprovider` was used because this Windows environment's cache-provider shutdown previously hung after completed tests.

Changed in this verification task: the local-only acceptance harness gained the required matrix and A–P probes, safe applied-search summaries, long-fixture and isolation checks, and refreshed disposable-account cleanup. This record and its roadmap reference were added. **No production code, public API/SSE contract, migration, environment file, or CTX-02 code changed.**

## Severity and exclusions

- **P0:** none observed. No cross-actor data leak or unauthorized/consequential wrong-entity mutation was observed.
- **P1 context:** no confirmed stale-term execution, lost untouched typed price, wrong ordinal/card mutation, >100-history regression, or persisted-state corruption in completed searches. However the required Matrix 3 is **gate-blocking regardless of label**: its exact bare-title opener routed away from search 5/5 times, leaving the replacement path untested in that matrix. This must be resolved or explicitly re-scoped before a GO.
- **P2 context/experience:** unnecessary read-only permission prompts (A5/G2), unsupported natural phrasings routed to document fallback instead of focused clarification (B2/M2), `new only` refinement exhaustion, no-price-anchor `cheaper` choosing a card, and incomplete natural correction/generalization coverage. Repeated `MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED` on several selected phrasings is a separate runtime/terminal diagnostic, not a successful context result.
- **P3:** no new context-relevant cosmetic issue established.
- **Explicitly excluded from this context verdict:** Product relevance/ranking and exact-match labels; the previously observed cross-owner HTTP 500 mapping despite fail-closed isolation; stale cart badge/UI synchronization; Redis, semantic/long-term memory, token budgeting, same-session serialization, Kafka, and unrelated marketplace behavior. The prior overall rollout NO-GO in `ai-context-ctx-01-rc-final.md` remains separate.

**Next step at the time of this gate:** keep CTX-01 context acceptance at **NO-GO** and investigate Matrix 3 and incomplete natural-language/terminal cases. The later 01E focused fix addresses the bare-title opener, but a full rerun of this context-only gate remains pending. CTX-02 implementation is not part of this record.
