# AI-LIST-EVAL-P0-03 Regression Comparator And Evidence Manifest

## Status

Implemented and verified as the third deterministic AI-LIST evaluation slice.
The slice advanced the AI counter from `2/3` to `3/3`. The mandatory
`AI-LIST-EVAL-CLEAN-P0-01` cleanup is now complete and resets the counter from
`3/3` to `0/3`. No feature successor was started.

## Scope

This checkpoint compares the current P0-01 report and P0-02
decision/observability bundle with a pinned Agent-owned evidence manifest. It
does not change proposal behavior, runtime configuration, APIs, provider
execution, or rollout state.

Versioned contracts:

- manifest: `ai-list-offline-evidence-manifest-v1`
- candidate bundle: `ai-list-offline-evidence-bundle-v1`
- comparison: `ai-list-offline-regression-comparison-v1`
- comparator: `ai-list-eval-p0-03-comparator-v1`

The comparison may emit `REGRESSION_PASS` for offline contract conformance. Its
only rollout decision is `BLOCKED`, and `releaseAuthorized` is always `false`.

## Pinned Evidence

Manifest version `ai-list-eval-p0-03-manifest-v1` pins:

- manifest SHA-256:
  `0f9b8c3ece2623d8523e189a96dd1d5f7b1e279fd9720403c4e6e25f60aa3a61`
- fixture SHA-256:
  `3a05af2c58db3fe0836b3de327ede2e5b4808f0d3ad1b875264823cffed24ee3`
- baseline report SHA-256:
  `3d16fe7020286923dbb07aba3773c5ef891eba9828aed1d3e84f4be5e9f6cd3c`
- timestamp-bound report SHA-256:
  `4eef876c8c06a3c0c1656710580f3215e0695f7d6307889e1310d936be0a7acc`
- baseline P0-02 decision SHA-256:
  `afc03104084db7b3e095fb6ec044869550e595dcc78a01c8b19c38629de507d3`
- baseline observability SHA-256:
  `192aca78603e8dbcdeb0db0f7a3bee779a0f1953aa84a8450df735544751b8b3`

It also pins:

- the P0-01 report, runner, fixture, proposal, media-tool, and instruction
  versions
- deterministic seed `20260720`
- 13 ordered unique case IDs and empty failure coverage
- 8 exact report metric identities, baseline values, and coverage counts
- 8 exact `EQ 1.0000` provisional thresholds
- zero provider requests, input tokens, output tokens, and estimated cost
- simulated latency label `NON_PRODUCTION_SIMULATED`
- baseline simulated p95 `42 ms` and maximum bounded regression `5 ms`
- 28 ordered P0-02 gate names and the six default-off blocker prefixes
- permanent offline-authority blocker
- 15 ordered low-cardinality observability metric names
- production quality, latency, and cost statuses `UNKNOWN`

The manifest digest is also pinned in comparator source. A valid but edited
manifest fails with `MANIFEST_DIGEST_MISMATCH`.

## Comparison Checks

Every result contains exactly these ordered checks:

1. manifest integrity
2. candidate bundle schema
3. schema/version/fixture/runner identity compatibility
4. report, timestamp-bound report, decision, and observability digests
5. exact case count, ordered IDs, uniqueness, and failure coverage
6. metric identity, coverage, and value non-regression
7. exact threshold identity and no weakening
8. zero provider requests, tokens, and cost
9. simulated-latency label, non-production boundary, and bounded regression
10. exact observability schema and metric cardinality
11. blocked decision, no release authorization, and offline authority
12. kill-switch/default-off gate and blocker precedence
13. absence of production quality, latency, cost, pricing, approval, or
    readiness claims
14. bounded, non-future evidence freshness

Missing, extra, or duplicate cases and observable metrics fail. Report metric
sets are exact, so an extra metric also fails. Candidate threshold comparators
and values must match the pinned `EQ 1.0000` contract; weakening is rejected.

The bounded simulated-latency allowance is an offline regression guard only.
It is not a production SLO, and simulated data can never become production
evidence.

## Fixed Reason Codes

The machine-readable reason enum covers:

- invalid/tampered manifest or missing/malformed/incompatible bundle
- identity and each evidence-digest mismatch
- case-set/count drift and failure-coverage loss
- metric-set/value/coverage regression
- threshold-set drift or weakening
- nonzero provider requests, tokens, or cost
- simulated-latency label, production-claim, or bound regression
- observability schema/cardinality drift
- non-blocked decision, release authorization, or missing offline authority
- kill-switch/default-off precedence drift
- production quality/latency/cost or readiness claims
- future, stale, or invalid evidence windows

Reasons are deduplicated in fixed check order. The comparison includes a
SHA-256 digest over every output field except the digest itself, so identical
inputs and evaluation timestamps produce identical output.

## Freshness Contract

The manifest and current bundle use:

- generated at: `2026-07-20T00:00:00Z`
- expires at: `2026-07-27T00:00:00Z`
- maximum evidence age: `604800` seconds

The CLI requires an explicit timezone-aware evaluation timestamp. It never
uses the wall clock. Evidence generated after that timestamp, evaluated after
expiry, or older than the bounded maximum fails.

An expiry window longer than the same seven-day maximum also fails with
`EVIDENCE_WINDOW_INVALID`, even when the comparison occurs before the proposed
expiry.

## Cleanup Reconciliation

`AI-LIST-EVAL-CLEAN-P0-01` made no threshold, fixture, manifest, digest, flag,
or release-policy change. It:

- consolidated strict camel-case schemas, canonical JSON, model and
  timestamp-bound SHA-256 generation, safe decimal/timestamp parsing, and
  freshness classification in one Agent-owned evaluation helper
- removed redundant evidence-ID checks already enforced by the strict input
  schema
- made manifest metric ratios, threshold binding, gate precedence, and the
  exact ordered 15-name observability set explicit schema invariants
- added deterministic rejection of overlong evidence windows while retaining
  fixed reason ordering

All pinned SHA-256 values and the current deterministic comparison digest
remain unchanged. Offline `PASS` and `REGRESSION_PASS` remain unable to
authorize rollout.

## Offline Commands

From `agent-service`, compare the current deterministic bundle:

```powershell
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --evaluated-at 2026-07-20T12:00:00Z
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --evaluated-at 2026-07-20T12:00:00Z --digest
```

Compare a supplied candidate JSON:

```powershell
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --candidate candidate-bundle.json --evaluated-at 2026-07-20T12:00:00Z
```

Emit strict schemas:

```powershell
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --schema manifest
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --schema bundle
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evidence_comparator --schema comparison
```

The current pinned candidate emits `REGRESSION_PASS`, exits zero, remains
`BLOCKED`, and authorizes no release. A regression emits `REGRESSION_FAIL` and
exits nonzero.

## Exclusions And Limitations

- The comparator supplies offline contract evidence only.
- It does not create production quality, latency, cost, privacy, policy, or
  rollout evidence.
- It creates no dashboard, cohort, alert, rollback automation, API, UI, or
  runtime flag.
- It makes no network, Product media, provider, or external OpenSearch call.
- It changes no migration, dependency, environment, credential, or pricing
  state.
- AI evaluation cleanup is complete; the AI counter is `0/3` and paused.
