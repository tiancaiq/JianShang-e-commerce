# REFINE-FIX-01E — bare marketplace query routing

Status: **focused GO in the context-management worktree** (2026-10-02). Final
CTX-01 context-only acceptance and rollout remain separate; CTX-02 has not begun.

## Root cause and scope

The bare opener `RC Delivered Return Fixture Harbor Cart Supply` contained
`Return` and `Cart`. The scope classifier checked broad marketplace-support
substrings before considering a title-like bare phrase, so it marked the turn
`KNOWLEDGE_RAG`. The provider then required `retrieve_help`; repeated model
decisions could end in the missing-document fallback without a Product search.
`Search for RC Delivered Return Fixture now` took the earlier explicit-discovery
branch and did search, confirming this was Agent routing rather than Product
visibility. The failing opener had no contextual refinement or active search;
the discovery Skill and `search_listings` were available, but the grounding
classification forced the competing help tool. There is no separate intent
classifier selecting a search tool on this path.

The scope classifier now emits `BARE_MARKETPLACE_QUERY` with `LISTING_DATA`
grounding for a 2–12-token, non-question, noun-like phrase. Short phrases
without a support-substring keep their existing `SHORT_PRODUCT_PHRASE` path;
the new signal chiefly catches longer titles and short titles containing words
such as `return` or `cart`. The model still makes the first decision and supplies
all search arguments; policy still validates any proposal. The provider and
discovery Skill now tell the model to search an identifiable title or ask one
focused clarification, not substitute help retrieval. No broad forced-search
router, backend-authored query, public API, migration, or Product change was
added. The existing five-step LISTING_DATA grounding check rejects an
ungrounded document-style response and feeds one in-loop `GROUNDING_REQUIRED`
observation; no new terminal guard was necessary.

Question/help framing remains outside this bare-query signal. Exact one-word
`returns`, `seller`, and `shipping` are ambiguous with no forced search;
explicit help questions retain their help path. General-knowledge and joke
requests remain out of scope. The joke boundary was made explicit because the
pre-change classifier treated `Tell me a joke` as in scope.

Related natural turns were inspected but not broadened into this fix:
`something under a grand`, `new only`, and `wireless only` are contextual
refinements or ambiguity; `forget Harbor Cart Supply, just RC Delivered Return
Fixture` and the noisy `uh yeah ... search ... don't include the store name`
are replacement-recognition cases. They do not share the opener's
support-substring/forced-help root cause.

## Verification

- Pre-change 01E characterization: 8 failures, including the exact title,
  title/store, long unrelated product title, and no-search terminal behavior.
- Final full Agent suite: **1,048 passed, 47 environment skips, 464 subtests**.
  Focused scope/01E suite: **28 passed, 94 subtests**. Real MySQL persistence
  integration: **24 passed**. Test harness syntax check passed.
- Fresh-session matrices on the isolated worktree Agent: Matrix 1 **5/5**
  (unloaded final rerun); Matrix 2 **5/5**, Matrix 3 **5/5**, and Matrix 4
  **5/5** (final combined run). Matrix 3 also passed a separate 5/5 run; every
  opener executed a Product search and every second turn searched exactly
  `RC Delivered Return Fixture` without the store words.
- Natural bare-query smokes on the final candidate: `red Logitech keyboard`,
  `27 inch monitor`, `wireless mouse`, and `RC Delivered Return Fixture` each
  executed exactly one matching search (**4/4**). The previously run laptop,
  keyboard, and Dell-monitor refinement smokes passed all **8/8** turn checks,
  preserving the typed price filters and query-only attributes.
- In a session seeded with more than 100 messages, `Dell monitor` searched
  `Dell monitor`; `Search just monitor` searched `monitor` without Dell. Both
  checks and the row-count check passed.
- Actor A, actor B, and actor A's separate new session each searched only their
  own bare query (**3/3**); cross-session access was denied. Help (3),
  out-of-scope (2), and one-word ambiguous (3) live controls completed without
  Product searches (**8/8**).

One combined matrix run performed concurrently with the full test suite had a
single `MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED` on Matrix 1's second turn
(4/5 in that run); unloaded Matrix 1 then passed 5/5. An earlier combined
run had one Matrix 3 replacement miss (4/5), followed by two independent
Matrix 3 5/5 passes. These are retained as live-model/runtime variability,
not silently discarded. They warrant the separate final CTX-01 context-only
gate before rollout; this focused GO is not a final release sign-off.

The acceptance harness creates disposable accounts, verifies executed search
observations and persisted turns, disables its two accounts after each run,
and now reports a missing long-session terminal without malformed SQL. Its
isolation sequence sends actor A's turn before creating actor A's next new
conversation, matching the active-session contract.

## Boundaries and next step

No migration, public contract, Redis, memory, Kafka, Product ranking, cart UI,
or CTX-02 work. Rerun the final context-only CTX-01 gate against this
worktree; do not infer rollout approval from the focused 01E result.
