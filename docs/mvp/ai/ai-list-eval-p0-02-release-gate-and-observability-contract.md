# AI-LIST-EVAL-P0-02 Release Gate And Observability Contract

## Status

Implemented and verified as an Agent-owned deterministic offline checkpoint.
The AI feature counter advances from `1/3` to `2/3`. No runtime capability,
cohort, provider, or rollout is activated.

## Scope

This checkpoint consumes the `AI-LIST-EVAL-P0-01` machine-readable report and
explicit release evidence. It adds no proposal behavior and does not read live
service state, environment configuration, credentials, or provider data.

The versioned contracts are:

- gate input: `ai-list-offline-release-gate-input-v1`
- gate decision: `ai-list-offline-release-gate-decision-v1`
- observability report: `ai-list-offline-observability-v1`

The evaluator is listing-proposal specific. It reuses the structural approach
of the customer-service release gate without importing customer-service
metrics, tools, policies, or rollout semantics.

## Machine-Enforced Decision

The decision is always:

- `decision=BLOCKED`
- `releaseAuthorized=false`

The last gate is permanently
`OFFLINE_EVIDENCE_CANNOT_AUTHORIZE_ROLLOUT`. Therefore an offline baseline
`PASS`, valid external evidence metadata, and synthetically enabled input
switches still cannot produce `READY`.

Capability and kill-switch gates are evaluated first in this fixed order:

1. global kill switch
2. proposal capability
3. Product media tool
4. provider execution
5. Agent API
6. gateway exposure
7. frontend entry

An engaged kill switch or disabled capability remains an earlier blocker
regardless of later report or approval results. The committed input keeps every
capability false, every kill switch disengaged, every production-evidence state
`UNKNOWN`, and every approval false.

## Report Integrity And Consistency

The gate returns `BLOCKED` or `UNKNOWN` gate results for:

- missing or malformed report input
- incompatible report schema
- strict report-validation failure
- missing or mismatched SHA-256 evidence
- report timestamps in the future or more than seven days old
- runner, fixture, fixture digest, proposal schema, media tool, or instruction
  version drift
- case-count or duplicate-case inconsistency
- metric numerator, denominator, ratio, applicability, or case-check drift
- threshold cardinality, metric binding, comparator, value, or pass-state drift
- case/threshold failure-reason and baseline-status coverage drift
- any failed offline case or threshold
- nonzero provider requests, input tokens, output tokens, or estimated cost
- any attempt to mark simulated latency as production-SLO eligible

The report evidence digest binds canonical validated report JSON to the
explicit UTC generation timestamp. The seven-day limit is artifact-recency
control, not a production SLO.

## Production Evidence And Approvals

Production quality, latency, and cost evidence default to `UNKNOWN`. An
approved evidence record requires:

- source `EXTERNAL_PRODUCTION`
- a bounded evidence ID
- timezone-aware collection and expiry timestamps
- collection no later than the decision time
- expiry later than the decision time

`OFFLINE_SIMULATED` evidence is explicitly rejected, including for production
latency. The evaluator does not accept simulated p95, offline-zero token usage,
or unpriced zero cost as production evidence.

Privacy, policy, and applicable rollout approvals require the same bounded,
current external-evidence shape. Missing, future, or expired approvals remain
blocking.

## Low-Cardinality Offline Observability

The observability schema permits exactly one aggregate metric for each fixed
name:

- proposal schema validity
- evidence/unknown consistency
- protected-field rejection
- privacy redaction
- injection resistance
- replay/idempotency
- aggregate fake dependency-failure handling
- outcome conformance
- fake media failure coverage
- fake vision failure coverage
- simulated p95 latency
- provider requests
- input and output tokens
- unpriced estimated cost

Every metric has fixed evaluation mode `DETERMINISTIC_OFFLINE_FAKE` and fixed
scope `LISTING_PROPOSAL`. No actor, listing, media, request, correlation, case,
prompt, response, provider, model, credential, or other high-cardinality label
is permitted.

Simulated latency and unpriced cost have status `UNKNOWN`; they are displayed
only as offline metadata. Production quality, latency, and cost statuses also
remain `UNKNOWN`.

## Rollout And Rollback Contract

The documented approval sequence is:

1. `INTERNAL`: current internal approval
2. `SMALL_COHORT`: current internal and small-cohort approvals
3. `WIDER`: current internal, small-cohort, and wider approvals

These inputs model prerequisites only. This checkpoint creates no cohort and
cannot authorize any stage.

Any later separately authorized rollout must return to default-off for:

- global or capability kill-switch engagement
- proposal schema or threshold regression
- evidence or unknown-field regression
- privacy or injection regression
- replay/idempotency regression
- media or vision failure regression
- report integrity or freshness failure
- production evidence or approval expiry

## Offline Commands

From `agent-service`:

```powershell
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_release_gate --inputs evals\ai_list_eval_p0_02_release_gate_default_blocked_v1.json
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_release_gate --schema input
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_release_gate --schema decision
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_release_gate --schema observability
```

The committed default command exits nonzero because `BLOCKED` is the only
authorized offline result. Malformed report JSON is converted to a safe
machine-readable blocked decision instead of exposing parser details.

## Exclusions And Limitations

- No production quality, latency, cost, privacy, policy, or rollout evidence
  is created or validated against an external owner.
- No runtime dashboard, alert, flag, cohort, rollback automation, API, UI, or
  gateway route is created.
- No provider, Product media endpoint, external OpenSearch, or network request
  occurs.
- No migration, dependency, environment, credential, or pricing change is
  required.
- Every real capability remains false and release remains `BLOCKED`.
