# REFINE-FIX-01A acceptance — 2026-09-29

## Verdict and boundary

**GO for the narrow 01A executed-search snapshot contract; not a GO for the multi-turn refinement experience or CTX-01-RC release sign-off.** When `search_listings` executes, its validated arguments become one bounded, actor/session-owned snapshot in Agent persistence and private model context. A new executed search replaces the old one; a completed failed search, an expired snapshot, or a legacy observation with no snapshot cannot silently revive an older search. Live turns also show that the model sometimes does not execute a requested refinement, which is the planned 01B/01C boundary, not evidence that 01A stored the wrong executed state. No 01B or 01C change was made.

Scope: `REFINE-FIX-01A`, with CTX-00/CTX-01 regression coverage. Worktree branch `codex/context-management`. This acceptance uses the already-uncommitted 01A implementation; no production code was changed in this verification task.

## Implementation and internal contract inspected

1. `MarketplaceAgentV2ToolRegistry._search` creates `ExecutedSearchSnapshot` only after a successful Product search response, from validated `SearchListingsArguments` forwarded to Product. It does not infer the next proposal, rewrite query concepts, or assert typed RAM support.
2. `MarketplaceAgentV2Service` adds the observation to the completed assistant's existing `agent_messages.actions_json`. `_persistable_observation` sets `resultsDisplayed` from actual validated search-card attachments in that completed message. A completed failed search persists a safe failure marker with no applied snapshot.
3. `ContextBuilder.build_turn` reads the actor/session-owned prior messages through the CTX-00 latest-100-row path, selects the newest search observation independently of the last-12-observation cap, and projects one `latestSearch` into the private `AgentContext`. The standard observation projection omits duplicate `appliedSearch` and result cards. Out-of-scope suppression also omits `latestSearch`.
4. The snapshot has `query`, `categoryId`, `categoryName`, `condition`, `minimumPrice`, `maximumPrice`, `currency`, `city`, `county`, `limit`, `observedAt`, `expiresAt`, and `resultsDisplayed`. Query is the executed search text after the existing exact typed-price cleanup, not a separately verified product concept. Prices are decimals serialized safely as strings. The five-minute expiry is validated and checked at reconstruction. `resultsDisplayed` attests to a completed Agent attachment, **not** browser rendering.
5. The **new snapshot** deliberately contains no customer credentials, raw provider response, Product payload, listing cards, inventory guarantee, typed RAM attribute, durable goal, semantic memory, or cross-session state. The pre-existing observation/attachment path still carries its separately bounded listing cards. A successful zero-result search is still an executed search with `resultsDisplayed=false`; the flag distinguishes it from a displayed result set.
6. New successful search observations replace the older projection, even for a new product. A newer failed/invalid/legacy/expired search observation suppresses an older one rather than falling back. Older action JSON without optional `appliedSearch` still parses and gives no active snapshot. There was no migration/backfill or public API/SSE contract change. The deliberate private model context change is `latestSearch` under internal schema version `ctx-01-v2`.

## Evidence and exact executed fields

The local-only harness `tools/ctx01-rc-runtime-acceptance.mjs --refine-01a` ran against an isolated host Agent at `127.0.0.1:18086` using this worktree, live `gpt-5-mini`, Agent MySQL, Keycloak/Auth, and Product. The existing shared Agent container and frontend were untouched. Optional host-unavailable help corpus was disabled only in the isolated process; no environment file was edited. Run `mulphhad` created two buyer-only disposable accounts and disabled both in `finally`. It inspected an allowlist of Agent-owned `actions_json` fields; it printed no credentials or raw provider payloads. Snapshot values below are the executed validated search arguments persisted by 01A, not a guess from assistant prose. The automated tool test additionally compares those fields with the Product request object.

| Scenario | Result at 01A boundary | Expected latest snapshot | Actual executed/persisted snapshot |
| --- | --- | --- | --- |
| A: laptop under $1000 | PASS | laptop, `maximumPrice=1000`, USD, displayed | `query=laptops`, `maximumPrice=1000`, `currency=USD`, `resultsDisplayed=true` |
| A: correct to $1500 | NOT EXECUTED (01B) | If searched: max 1500, no 1000 | No second search observation; model asked permission. Existing max-1000 snapshot remained the latest executed search. |
| B: new laptop, then under $1000 | NOT EXECUTED (01C) | If searched: NEW + max 1000 | First search `query=laptop`, `condition=NEW`, displayed; second turn ended `MODEL_RESPONSE_UNSUPPORTED` before search. |
| C: 16GB → 32GB RAM | PASS | current query 32GB only; no false typed RAM | First `query=laptop 16GB RAM`, then `query=laptop 32GB RAM`; neither has a typed RAM field; both had `resultsDisplayed=false` because Product returned no usable cards. |
| D: keyboard → wireless → under $100 → no RGB | PARTIAL (01B/01C) | Reflect each *executed* search only | First `query=keyboard`, displayed; wireless turn did not search; price turn ended `MODEL_RESPONSE_UNSUPPORTED`; subsequent no-RGB turn was not reached by this harness. No unsupported filter was invented in snapshot. |
| E: laptop <$1000 → monitors | PASS | monitor; no laptop/$1000 | Second executed `query=monitor`, no price/currency, displayed. |
| F: monitor <$300 → <$400 | NOT EXECUTED (01B) | If searched: max 400 | First executed `query=monitor`, `maximumPrice=300`, USD, displayed; correction completed without a search. |
| G: Los Angeles desk <$300 → <$500 | PASS | city retained; max replaced | Second executed `query=desk`, `city=Los Angeles`, `maximumPrice=500`, USD; no old 300 bound; `resultsDisplayed=false` for no cards. |
| B actor's independent keyboard session | PASS for 01A isolation | keyboard without A price/query | `query=keyboard`, no price, displayed. Cross-actor GET disclosed no A history, although it returned the pre-existing HTTP 500 error mapping instead of 403/404. |
| Deterministic new keyboard/monitor reset | PASS automated | old query/price cleared | Explicit second search proposals yielded keyboard and monitor snapshots with no inherited price. |
| Deterministic price/condition/location carry | PASS automated | 1500 supersedes 1000; NEW and city retained when proposed | Exact second Product request and reconstructed snapshot matched the proposal, with only one current value per supported field. |
| Failed/expired/legacy search | PASS automated | no stale active snapshot | Latest failed search, expired snapshot, or newer legacy search observation yielded `latestSearch=null`. |
| Persist/reload | PASS automated, including real MySQL | same supported snapshot after reconstructing context | Completed `actions_json` round-tripped via the normal persistence read; new service/ContextBuilder instance restored the prior executed search. The five-minute window applies. |

Live `resultsDisplayed` tracked both branches: laptop/monitor/keyboard searches with completed cards were `true`; RAM and Los Angeles desk searches with no cards were `false`. No live response asserted that returned listings were verified 32GB. Every completed synthetic live turn persisted one USER and one ASSISTANT; two turns completed with guarded `MODEL_RESPONSE_UNSUPPORTED` rather than a successful refinement search. The cross-actor GET denied data but mapped the ownership `ValueError` to HTTP 500, reproduced in the earlier CTX-01-RC control and unrelated to snapshot reconstruction.

## Automated verification and CTX-01 compatibility

The new focused acceptance test uses the real V2 tool registry, safe observation serialization, and ContextBuilder with explicit model proposals. It covers price supersession, condition and location carry-forward, RAM query replacement, both new-product resets, failed/expired/legacy suppression, and actor/same-actor-two-session isolation. Existing service tests cover completed-message display marking, no-display and failed-search markers, and service-level reconstruction; the real MySQL integration exercises action-JSON round trip and the latest-100-row/CTX-00 path. These tests prove what happens **if** the model proposes a valid search; they do not claim that 01A forces the model to search.

Automated results from this worktree, with `PYTHONPATH` set to this checkout's `agent-service/src`:

- Marketplace Agent V2 unittest discovery: **411 run, 3 skipped, 0 failures**. This includes the three new focused acceptance cases.
- Full Agent service unittest discovery: **1,052 run, 47 skipped, 0 failures**. Expected failure-path logs appeared; exit code was zero.
- Isolated MySQL Testcontainer persistence integration (`RUN_MYSQL_INTEGRATION=1`): **12 run, 0 skipped, 0 failures**, including action-JSON round trip, actor ownership, and the CTX-00 >100-row regression.
- `node --check tools/ctx01-rc-runtime-acceptance.mjs` and `git diff --check`: **pass**.

An initial broad unittest invocation accidentally imported the separately installed main-checkout package because this worktree's source path was not set. Its import errors were a runner-path mismatch. The corrected V2, full Agent, and MySQL runs above are the acceptance results.

CTX-01 checks exercised by the focused suite include recent-message order, latest cards/card order, out-of-scope filtering, actor/session ownership, safe observations, Skill projection, provider JSON privacy, Stop and response-only Retry. The internal provider context adds only one bounded `latestSearch`; trace/source identity remains private and prior observations do not duplicate the applied search. No browser acceptance was claimed: the shared frontend still points to the older container, and `resultsDisplayed` is a server-side completion indicator.

## Defects, changes, and remaining owners

No incorrect 01A snapshot or 01A isolation defect was reproduced, so **no production 01A fix or migration** was made. This task added the focused acceptance regression test, the `--refine-01a` local harness mode, this report, and a roadmap reference. It did not touch prompts, permission behavior, terminal recovery, SSE buffering, Redis, CTX-02, or general memory.

- `REFINE-FIX-01B`: redundant permission/no-search on A/F and incomplete carry-forward planning. These turns cannot establish the hypothetical corrected snapshot because no second search ran.
- `REFINE-FIX-01C`: guarded `MODEL_RESPONSE_UNSUPPORTED` on B/D and associated terminal/SSE behavior. No 01C recovery was implemented here.
- Existing API error mapping: a cross-actor history read returns 500 instead of an intentional 403/404; it exposes no session data and is not an 01A snapshot leak.

The verdict is limited to 01A state construction, persistence, reconstruction, freshness, replacement, and ownership. CTX-01-RC remains **NO-GO** for its separate full release gate.
