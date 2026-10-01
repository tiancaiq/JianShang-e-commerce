# REFINE-FIX-01D — explicit query replacement

Status: **REFINE-FIX-01D.1 focused GO in the context-management worktree; CTX-01 final rollout gate remains separate** (2026-10-01). This is not CTX-02.

## Defect and boundary

After an executed `RC Delivered Return Fixture Harbor Cart Supply` search, the shorter user phrase `RC Delivered Return Fixture` could retain `Harbor Cart Supply`. The 01B recognizer covered short attribute/typed-filter edits but did not identify a complete, shorter query as a replacement. The model could therefore carry forward stale free-text terms. A second live obstacle appeared because the word `Return` in the fixture title made the broad scope classifier route the bare follow-up to marketplace support rather than listing search.

01D recognizes a replacement only against the newest unexpired, actor/session-owned **executed** search snapshot. The message must be a nonempty, shorter restatement composed of terms from that query, with at least one term omitted. Existing price, condition, RAM, size, wireless, and no-RGB recognizers run first. A one-word restatement must match the prior query's final term, so `Dell monitor → monitor` qualifies but `monitor → 27 inch` does not. The scope override applies only to an `IN_SCOPE / MARKETPLACE_SUPPORT` collision grounded by that exact replacement, with no active workflow or pending interaction. It does not reclassify private, unsafe, or general knowledge requests.

The model still selects `search_listings` and supplies its arguments. Policy requires the proposed free-text query to contain exactly the replacement terms (word-order equivalent), while preserving unchanged category ID/name, condition, minimum/maximum price, currency, city, county, and limit. A stale-term or other mismatch is rejected before Product execution. The bounded repair observation uses `requestedEdit={field: QUERY, operation: REPLACE, value: <user restatement>}`, `preserveProductCore=false`, and `preserveUnchangedFilters=true`; it is not a backend-authored tool call. Only successful Product execution updates the canonical snapshot. No migration, public API field, or SSE contract changed.

Examples: `red Logitech keyboard → red keyboard`, `wireless mouse Harbor Cart Supply → wireless mouse`, and `Dell monitor` with maximum price 300 USD and condition NEW `→ monitor` with the same typed filters. `monitor → 27 inch`, `27 inch monitor → under $300`, and `27 inch monitor → actually 32 inch` remain ordinary refinements. `laptop under $1000 → Show me keyboards` remains a new-product request; `make it better` remains ambiguous.

## Verification

- Seven focused 01D tests cover the four replacements, preservation of every independent typed filter, non-replacement controls, bounded repair shape, stale-proposal rejection followed by one execution within three decisions, the title/scope collision, persistence/reload, and actor/session ownership. The replacement test uses an executed snapshot; it does not infer intent from a prior unexecuted model proposal.
- The complete Agent suite passed **1,086 tests (47 skipped)** after the final scope-guard narrowing. The separate real-MySQL persistence integration suite passed **12/12**. `node --check` and `git diff --check` passed.
- Live isolated worktree Agent (`127.0.0.1:18086`, `gpt-5-mini`, Product/Auth/MySQL) run `muoi9d3g`: **4/4 fresh-session flows, 10/10 turns passed**. The local harness reads allowlisted Agent-owned persisted `appliedSearch`, not assistant prose. Both disposable buyer accounts were disabled. The shared Agent container and frontend were not changed.

| Flow | Final persisted executed search | Decisions on final turn | Outcome |
| --- | --- | ---: | --- |
| Fixture/store removal | `RC Delivered Return Fixture`; no `Harbor Cart Supply` | 3 | PASS; one stale proposal rejected, one corrected search executed, one listing attached |
| Brand removal | `red keyboard`; no `Logitech` | 3 | PASS; one search executed |
| Typed price preservation | `monitor`, maximum 300 USD; no `Dell` | 4 | PASS; two rejected proposals, then one search executed |
| Ordinary refinement | `32 inch monitor`, maximum 300 USD; no `27 inch` | 4 | PASS; four turns each executed exactly one search |

No flow created a pending confirmation or executed a duplicate search. All completed within the existing five-decision ceiling. Earlier diagnostic passes found a transient Product `SEARCH_UNAVAILABLE` on one fixture retry and a Dell→monitor run that exhausted the decision budget before the repair guidance was strengthened. The passing full gate establishes focused acceptance, not a production reliability estimate; this model-dependent path remains worth repeating in the final context-only gate.

Decision: **REFINE-FIX-01D GO for its focused gate**. Run the final context-only CTX-01 gate next. Do not start CTX-02 or infer rollout approval from this slice.

## REFINE-FIX-01D.1 — explicit replacement command recognition

The later authenticated browser retest exposed a remaining 01D failure: after
`RC Delivered Return Fixture Harbor Cart Supply`, `Search just RC Delivered
Return Fixture` caused an unnecessary clarification instead of the shorter
search. The previous recognizer interpreted `Search just` as candidate query
words, so its shorter-subset check returned no `QUERY_REPLACEMENT` edit. The
model was then free to retain the old query or ask again.

01D.1 extends that same recognizer with anchored command wrappers: `Search
just X`, `Search only X`, `Search for X`, `Just search X`, `Use X instead`,
`Replace that with X`, and `Search X instead`. It strips only the wrapper, then
applies the existing shorter, self-contained, prior-query-subset rules to X.
It does not classify ordinary attribute/price/condition edits or ambiguous
phrases as full replacements, and a new-product request still follows its
existing reset path. The provider guidance now names these commands and
explicitly tells the model to retain independent typed filters. The model
still chooses `search_listings` and all arguments; policy rejects stale terms
or dropped typed filters and returns only a bounded repair observation. No
backend-authored search call, public contract, or migration was added.

Deterministic tests cover every wrapper, negative/ordinary refinements,
typed-filter preservation, stale proposal rejection followed by exactly one
execution within three model decisions, and recognition after persisted
context reconstruction with actor/session isolation. The final focused
`pytest` command (`01B`, `01C`, `01D`, `-p no:cacheprovider`) exited **0**:
35 passed, 0 failed, 0 skipped, 46 subtests passed. The full Agent suite
exited **0**: 1,043 passed, 0 failed, 47 skipped, 450 subtests passed. Real
MySQL persistence integration exited **0**: 12 passed, 0 failed, 0 skipped.

The prior focused `pytest` run had displayed passing indicators but did not
exit. A diagnostic traceback placed the wait in `pytest`'s cache provider,
inside `tempfile.mkdtemp` during `pytest_sessionfinish`; even one synchronous
test reproduced it, and disabling plugin autoload did not help. Disabling only
the optional cache provider made the focused and full runs exit cleanly.
The repository's documented `unittest` runner also exited **0** for the
focused 35 tests. No production cleanup behavior was changed for this
test-runner issue.

Final-build live acceptance used the isolated worktree Agent, real provider,
Product/Auth, Agent-owned MySQL observations, and disposable buyer accounts:

| Flow | Fresh sessions | Final executed search | Result |
| --- | ---: | --- | --- |
| A: `27 inch monitors → Under $300` | 5/5 | 27 inch monitor, maximum 300 USD | PASS |
| B: `monitors under $300 → 27 inch` | 5/5 | 27 inch monitor, maximum 300 USD | PASS |
| C: `27 inch monitors under $300 → Actually 32 inch` | 5/5 | 32 inch monitor, maximum 300 USD; no 27 inch | PASS |
| Fixture/store replacement | 5/5 | `RC Delivered Return Fixture`; no store terms | PASS |
| `red Logitech keyboard → Search just red keyboard` | 1/1 | `red keyboard`; no Logitech | PASS |
| `Dell monitor under $300 → Search just monitor` | 1/1 | `monitor`, maximum 300 USD | PASS |

The price smoke initially exhausted five rejected proposals before the
provider guidance was made explicit. A one-session diagnostic then passed
after three rejected proposals; a five-session repeat on the updated build
passed 5/5, and the final-build smoke passed again in two decisions. Policy
never executed the rejected proposals. All final-build replacement checks
recorded one successful search, no pending confirmation, and a terminal
response within five decisions; the harness verified one persisted USER and
one final ASSISTANT per turn. Each harness run disabled its two disposable
buyers.

Authenticated browser acceptance on the final build repeated the exact
failure sequence. `Show me RC Delivered Return Fixture Harbor Cart Supply
products.` completed an executed zero-result search; `Search just RC
Delivered Return Fixture` then executed `search_listings`, returned the one
Product-validated fixture attachment, reached `done`, and asked no redundant
clarification or confirmation. Its safe evaluation panel reported two model
calls, `SUCCEEDED:RESULTS_AVAILABLE`, and
`message_started → activity → tool_completed → text_delta → attachments → done`.
Refresh restored both USER/ASSISTANT pairs and the attachment exactly once.
The browser's disposable buyer is disabled after the test.

Decision: **REFINE-FIX-01D.1 GO for the focused gate**. Run the final
context-only CTX-01 gate next. This does not approve CTX-01 rollout or start
CTX-02.
