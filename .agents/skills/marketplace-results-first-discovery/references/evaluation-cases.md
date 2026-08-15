# Results-First Discovery Evaluation Cases

## Contents

- Automated acceptance matrix
- Complete regression conversation
- Browser evaluation
- Evidence requirements
- Definition of done

## Automated acceptance matrix

### 1. Understandable broad request

Input: `key organizer`

Verify:

- search executes immediately;
- no blocking clarification when matches are useful;
- top-K validated attachments appear in the terminal response; and
- any refinement appears only after the listings.

### 2. Useful low-confidence results

Provide low-confidence but useful validated key-organizer results.

Verify:

- exactly `LOW_CONFIDENCE_PREVIEW_K` or all available results, whichever is smaller, appear;
- content includes a concise uncertainty caveat;
- the user is not asked for permission to display; and
- optional suggestions are grounded in returned facets.

### 3. Contextual subtype refinement

After key-organizer results, input: `General`

Verify:

- the subtype preference is committed;
- active query remains key organizer;
- refined search or filtering executes immediately; and
- updated results display without confirmation.

### 4. Confirmation discipline

Input: `yes`

Verify:

- confirmation is consumed only when a persisted `pendingAction` exists;
- no pending display confirmation is created for ordinary listings; and
- the same confirmation is not requested again.

### 5. Location scope

Input: `anywhere`

Verify:

- all-location scope is stored;
- active discovery context is preserved; and
- location is not asked again.

### 6. Attachment invariant

For every response claiming results are found, shown, or compared, verify validated listing attachments exist in that same response. Reject text-only result claims.

### 7. No relevant results

Provide zero validated relevant matches.

Verify:

- the answer truthfully reports no relevant active listings;
- no cards are rendered;
- no subtype, budget, condition, location, or color interrogation follows; and
- technical failure is not mislabeled as no inventory.

### 8. Results too irrelevant

Provide candidates below the useful-preview threshold.

Verify:

- unrelated candidates do not appear;
- one focused clarification is asked at most once; and
- the clarification answer leads directly to search and display.

### 9. Facet-grounded suggestions

Verify every suggested subtype exists in validated facets. Reject model-invented brands, categories, conditions, prices, or locations.

### 10. History refresh

After completion, reload once and verify every user message, assistant message, attachment, and refinement restores exactly once.

### 11. Existing direct-response isolation

Verify greetings, thanks, cancellation, and direct non-discovery answers do not call listing search or display search activity/cards.

### 12. Product safety

For every displayed result, verify it is active, Product-revalidated, relevant, not a duplicate, not an unwanted accessory, and has required public fields.

## Complete regression conversation

Reproduce the clarification-loop bug as a stateful test. The workflow must:

- not require the user to repeat `General`;
- not require repeated `yes` confirmations;
- not ask permission to show already available listings;
- display useful listings no later than the first resolved clarification; and
- preserve the answered fields during subsequent refinements.

Record state after every turn and assert `clarificationCount <= 1` before the first display.

## Browser evaluation

Run this exact sequence in one fresh authenticated Marketplace Agent V2 conversation, stopping at the first defect:

1. `key organizer`
2. `General`
3. `under 50`
4. `anywhere`
5. `the second one`
6. `show me a cheaper one`
7. `never mind`

Do not send the next turn until the previous turn reaches an authoritative terminal state. Do not automatically retry or resend uncertain messages.

For each turn, record only safe evidence:

- active query;
- known preference categories and values;
- clarification count;
- pending action category;
- proposed and executed allowlisted tool;
- validated listing count and listing identities permitted by the evaluation environment;
- attachment count rendered;
- visible customer-facing response;
- fixed SSE event order;
- terminal persistence status; and
- exact-once state after the final refresh.

Never record prompts, hidden reasoning, raw model output, vectors, scores, credentials, private tool arguments, or provider errors.

## Layered test expectations

Add proportionate coverage at the owning layers:

- policy/unit tests for immediate-search and clarification budgets;
- Product adapter/contract tests for validated result counts, relevance, facets, and failures;
- orchestrator tests for repeated clarification/tool-call rejection and contextual short replies;
- SSE tests for activity, terminal text, attachments-after-validation, Stop, and retry;
- persistence tests for state and exact-once history;
- frontend parser/component tests for top-K cards, caveats, optional refinements, and reload; and
- production/demo build checks.

No live provider call belongs in automated tests.

## Definition of done

The behavior is complete only when:

- understandable product queries search immediately;
- useful top-K listings appear before optional refinement;
- low confidence does not trigger repeated confirmation;
- clarification is limited to genuinely ambiguous concepts;
- answered fields are not requested again;
- listing claims always match attached validated listings;
- refinement turns progress the current workflow rather than restart it;
- exact-once history restoration passes; and
- the browser sequence passes without private-data exposure.
