---
name: marketplace-results-first-discovery
description: Use when designing, implementing, reviewing, or testing Marketplace Agent product discovery, listing search, result confidence, top-K display, clarification questions, result attachments, search facets, and conversational refinement. Enforces results-first discovery and prevents repeated confirmation or clarification loops.
---

# Marketplace Results-First Discovery

## Mission

Help users discover what is actually available. Do not turn product discovery into a questionnaire.

For a reasonably understandable product request, enforce this default flow:

1. Search current marketplace listings.
2. Validate and rank relevant listings.
3. Show the strongest top-K listings immediately when useful matches exist.
4. State uncertainty briefly when matches are imperfect.
5. Ask at most one optional, inventory-grounded refinement question after showing listings.

The refinement must never block the initial result display.

## Primary invariant

Prefer:

`search -> show top-K -> offer optional refinement`

Never default to:

`ask category -> confirm category -> ask permission -> ask location -> ask permission again -> summarize results -> ask permission to display`

Clarification is an exception. Do not require permission to display ordinary validated public listings.

## Required workflow

1. Read [references/discovery-contract.md](references/discovery-contract.md) before changing discovery routing, search tools, observations, attachments, facets, session state, or clarification behavior.
2. Read [references/evaluation-cases.md](references/evaluation-cases.md) before adding or running tests, reviewing a change, or declaring the behavior complete.
3. Inspect the current Agent, Product, persistence, SSE, and frontend contracts before editing. Preserve authorization, revalidation, exact-once persistence, Stop/retry/history, and privacy boundaries.
4. Determine whether the primary product concept is understandable from the current turn and usable session context.
5. Search immediately for an understandable concept even when optional preferences are missing.
6. Ask one blocking clarification only when the concept is genuinely ambiguous and a broad search would likely mislead.
7. Classify the validated search observation as useful, useful-but-imperfect, too irrelevant, or no relevant results.
8. Render only Product-revalidated listing attachments. Never fill top-K with unrelated or accessory listings.
9. Derive optional refinements only from validated facets.
10. Persist state so short follow-ups refine the active workflow instead of restarting it.
11. Add backend safeguards and tests; do not rely only on model instructions.

## Non-negotiable safeguards

- Maximum one blocking clarification before the initial result display.
- Maximum one optional refinement question after results.
- Never repeat an answered clarification or display confirmation.
- Never rerun an identical query and filter set in the same invocation.
- Never claim listings exist or are shown unless the same response contains validated listing attachments.
- Never expose raw scores, vectors, hidden reasoning, prompts, tool arguments, private IDs, or provider internals.
- Preserve Product ownership of listing truth and revalidation; the Agent must not query Product storage directly.
- Preserve exact-once message and attachment restoration after refresh.

## Review standard

Reject a change when it:

- makes an understandable noun phrase wait for optional preferences;
- treats imperfect confidence as a reason to ask permission to show useful results;
- suggests a subtype absent from validated facets;
- says results were found without attachments;
- repeats `General`, `yes`, `anywhere`, or another resolved field;
- restarts discovery instead of composing a short contextual refinement;
- fabricates results to fill top-K; or
- passes unit tests but fails the browser sequence in the evaluation reference.

## Completion

Run the required automated and browser cases in [references/evaluation-cases.md](references/evaluation-cases.md). Report the observed search action, confidence classification, validated/displayed counts, attachments, refinement state, exact-once reload result, and any inventory limitation.
