# Results-First Discovery Contract

## Contents

- Mission and invariant
- Search and clarification decisions
- Result classification
- Top-K and confidence policy
- Search observation
- Refinements and state
- Attachment and backend invariants
- Required examples

## Mission and invariant

The Marketplace Agent should help users discover what is actually available, not behave like a questionnaire.

For an understandable request such as `key organizer`:

1. Search current listings.
2. Validate and rank relevant listings.
3. Show the strongest top-K immediately when useful matches exist.
4. State uncertainty briefly when matches are imperfect.
5. Ask one optional refinement after the listings.

Example response:

> I found several current key-organizer matches. These are the closest validated listings.

`[Top-K validated listing attachments]`

> Would you like me to narrow them by type, price, condition, or location?

The refinement question must not block the initial results.

## Search and clarification decisions

### Search immediately

Search immediately when the user gives a reasonably understandable product concept, including:

- `chair`
- `key organizer`
- `laptop`
- `phone`
- `desk`
- `gaming chair`
- `backpack`
- `used bicycle`
- `laptop under $1,000`

Do not block search because budget, subtype, brand, condition, location, color, size, or delivery method is missing. These are optional refinements.

### Ask one clarification only for genuine ambiguity

One blocking clarification is allowed only when a broad search would likely mislead, for example:

- `apple`
- `something good`
- `show me one`
- `the thing from before` without usable context
- a term that reasonably spans unrelated product categories

Do not clarify merely because confidence is imperfect, a preference is empty, multiple subtypes exist, the result set is large, several categories appear, or location is missing.

Maximum: one blocking clarification before initial results.

After the user answers, commit the answer, search immediately, and display results. Do not ask whether the user wants the search to run.

## Result classification

Classify only the validated observation.

### 1. Useful results

Show top-K immediately, followed by at most one optional refinement.

### 2. Useful but imperfect results

Show a smaller preview with a concise caveat. Low confidence is a communication condition, not automatically a confirmation condition.

Allowed:

> These are the closest current matches. Some may not be exact key organizers.

`[Three validated listing cards]`

> Would you like me to narrow these by category or price?

Forbidden:

> The results are low-confidence. Should I show them?

The user does not need to authorize the display of ordinary marketplace listings.

### 3. Results too irrelevant

Do not display unrelated listings. Ask one focused clarification. After the answer, search and show results without another confirmation about the same uncertainty.

### 4. No relevant results

Explain that no relevant active listings were verified. Do not continue preference interrogation unless relaxing one known filter could reasonably produce results.

## Top-K and confidence policy

Use configurable bounds:

```text
DEFAULT_DISCOVERY_TOP_K = 5
LOW_CONFIDENCE_PREVIEW_K = 3
MAX_DISCOVERY_TOP_K = 8
```

Every displayed listing must:

- be active;
- pass Product-service revalidation;
- meet the relevance threshold;
- match the primary product concept;
- be unique;
- not be an accessory unless requested; and
- include required public display fields.

Do not fill top-K with irrelevant candidates.

## Search observation

Use a strict structured observation equivalent to:

```json
{
  "status": "SUCCESS | FAILURE",
  "query": "key organizer",
  "normalizedConcept": "key organizer",
  "totalMatches": 10,
  "relevantMatchCount": 5,
  "retrievalConfidence": "HIGH | MEDIUM | LOW",
  "reason": "RESULTS_AVAILABLE | NO_RELEVANT_RESULTS | FILTERS_TOO_STRICT | SEARCH_UNAVAILABLE | INVALID_REQUEST | FORBIDDEN",
  "topResults": [],
  "facets": {
    "subtype": [],
    "condition": [],
    "priceBand": [],
    "location": []
  }
}
```

Candidate counts are not proof of available relevant inventory. The model may claim listings exist only when validated `topResults` are present.

## Inventory-grounded refinements

Offer at most one optional refinement after displaying results. Every suggestion must occur in validated facets.

If facets contain `General`, `Home & Garden`, and `Electronics`, the assistant may ask:

> Would you like me to focus on General, Home & Garden, or Electronics?

It must not mention an absent subtype.

## Clarification and confirmation budget

For one discovery workflow:

- maximum one blocking clarification before initial results;
- no confirmation to display validated public listings;
- maximum one optional refinement after results;
- no repeated question for an answered field; and
- no repeated permission request to display listings.

Treat `yes` as confirmation only when a real persisted `pendingAction` exists. Never create a pending confirmation merely to display search results.

## Conversation state

Maintain compatible state equivalent to:

```json
{
  "activeQuery": "key organizer",
  "knownPreferences": {
    "subtype": "General",
    "locationScope": "ALL"
  },
  "answeredFields": ["subtype", "locationScope"],
  "clarificationCount": 1,
  "resultsAvailable": true,
  "resultsDisplayed": true,
  "lastDisplayedListingIds": [],
  "pendingAction": null
}
```

Once `resultsDisplayed` is true, do not ask permission to display the same results and do not repeat the same clarification. Treat later turns as refinements, listing questions, or a new product request.

Interpret short replies through active context:

- `General` after key-organizer results: refine the active query to General.
- `under 50`: apply a maximum-price refinement.
- `anywhere`: set all-location scope and do not ask location again.
- `yes`: consume only a real persisted pending action.

## Attachment invariant

If assistant content says `Here are the results`, `I found these listings`, `Showing the top five`, or `These are the closest matches`, the same assistant response must contain validated listing attachments.

Forbidden:

> I found five listings. Would you like me to list them?

Required shape:

```json
{
  "role": "ASSISTANT",
  "content": "These are the closest current matches.",
  "attachments": [
    {
      "type": "LISTING_RECOMMENDATIONS",
      "items": []
    }
  ]
}
```

## Backend safeguards

Do not rely only on the model prompt. The backend must prevent:

- identical repeated clarifications;
- questions for answered fields;
- repeated confirmation for the same action;
- claims that results are shown without attachments;
- blocking display only because confidence is low;
- more than one blocking clarification before initial results;
- an identical query and filter execution during the same invocation; and
- presentation of unvalidated listings.

When the model proposes an invalid repeated clarification, append a structured safe observation instructing it to use current results or produce a terminal answer. Do not expose hidden reasoning or raw internal data.

## Required examples

### Useful results

User: `key organizer`

Search immediately. Respond with a concise introduction, top five validated listing cards, and one optional facet-grounded refinement.

### Imperfect results

User: `key organizer`

Show the closest three validated listings with a concise caveat, then one optional refinement. Do not ask display permission.

### Ambiguous concept

User: `apple`

Ask: `Do you mean Apple electronics or another kind of product?` Ask no second pre-search clarification.

### No results

User: `office chair`

If zero relevant listings are validated, answer: `I couldn't find any active office-chair listings right now.` Do not ask about budget, condition, location, or color.

### Refinement

After key-organizer results, user says `General`. Commit the subtype, search or filter immediately, and display updated listings without confirmation.
