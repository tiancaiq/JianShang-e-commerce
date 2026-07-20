# AI-CS-01E-B Release Gate And Offline Observability Contract

Status: implemented and verified offline on 2026-07-20.

Release: V3.

## Scope

This slice adds a deterministic machine-enforced release decision over the
`AI-CS-01E-A` report. It does not create feature flags, dashboards, cohorts,
runtime activation, provider calls, pricing, or production evidence.

The evaluator consumes:

- one raw `ai-cs-offline-evaluation-report-v1` payload;
- an explicit report-generation timestamp bound to the canonical report by a
  SHA-256 evidence digest;
- explicit enabled and kill-switch states for customer service, provider
  execution, retrieval, gateway exposure, and frontend entry;
- explicit external production-quality, production-latency, and
  production-cost approvals;
- explicit internal, small-cohort, and wider-rollout approvals;
- an explicit deterministic decision timestamp and target rollout stage.

It never reads environment variables or live service state.

## Decision Contract

The output schema is `ai-cs-release-gate-decision-v1`. The only decisions are:

- `BLOCKED`: one or more gates are blocked or unknown;
- `READY`: every gate passes for the requested synthetic or externally
  evidenced stage.

The evaluator returns `BLOCKED` when any of these conditions applies:

- a required capability is disabled;
- any corresponding kill switch is engaged;
- the report is absent, malformed, internally inconsistent, or has an
  incompatible schema;
- the report does not match the expected runner, fixture, prompt,
  tool-registry, or policy version;
- report evidence is missing or its SHA-256 digest does not match;
- the report timestamp is in the future or more than seven days old at the
  explicit decision time;
- the offline baseline or any threshold regresses;
- required fake tool, model, or OpenSearch-style failure coverage is missing;
- production-quality, production-latency, or production-cost approval is
  missing, not yet valid, or expired;
- the applicable rollout stage or one of its prerequisite stages lacks
  approval.

Kill-switch and default-off gates are evaluated first and always force
`BLOCKED`, even if every report and approval gate passes.

The seven-day report recency rule is an evaluation-artifact freshness control,
not a production SLO. The report timestamp remains explicit input evidence
because the deterministic 01E-A report contains no wall clock.

## Production Evidence Boundary

Offline annotated-claim faithfulness and simulated latency are not production
quality or latency evidence. Zero provider requests, tokens, and cost are
offline metadata and are not pricing evidence.

The production-quality, production-latency, and production-cost gates remain
`UNKNOWN` and therefore block release unless each has:

- `approved=true`;
- a bounded external evidence ID;
- a timezone-aware approval time;
- a later, still-current expiration time.

The evaluator stores no evidence body, pricing assumption, customer data, or
runtime secret.

## Offline Observability Contract

The dashboard/report schema is `ai-cs-offline-observability-v1`. It permits
only the fixed `OFFLINE` evaluation mode, `LISTING` source scope, and the
bounded dependency labels `NONE`, `TOOL`, `MODEL`, and `OPENSEARCH`.

Metrics:

| Metric | Evidence |
| --- | --- |
| Retrieval precision and recall ratios | Offline measured |
| Annotated-claim faithfulness ratio | Offline measured |
| Citation validity and completeness ratios | Offline measured |
| Actor-isolation ratio | Offline measured |
| Rejected stale/deleted-source ratio | Offline measured |
| Allowlisted-tool-boundary ratio | Offline measured |
| Aggregate fake-failure-handling ratio | Offline measured |
| Tool/model/OpenSearch fake-failure coverage ratios | Offline measured |
| Simulated p95 latency | Simulated, not a production SLO |
| Offline input/output tokens | Zero-cost offline metadata |
| Offline estimated cost | Unpriced zero-cost metadata |

No actor, listing, session, correlation, case, prompt, passage, response,
credential, or other high-cardinality label is allowed.

## Rollout And Rollback Contract

Rollout approval is sequential:

1. `INTERNAL` requires current internal-rollout approval.
2. `SMALL_COHORT` requires current internal and small-cohort approvals.
3. `WIDER` requires current internal, small-cohort, and wider approvals.

This slice defines those gates but creates no cohort and activates no traffic.

Any later rollout must return to default-off when one of these fixed triggers
occurs:

- a kill switch is engaged;
- a quality threshold regresses;
- citation or actor-isolation behavior regresses;
- a stale or deleted source leaks into results;
- the approved dependency-failure budget is exceeded;
- production latency evidence expires;
- production cost evidence expires.

Runtime thresholds and evidence are intentionally not invented here.

## Offline Commands

Generate the 01E-A report and evaluate it with explicit inputs:

```powershell
python -m msb_agent_service.customer_service_evaluation > offline-report.json
python -m msb_agent_service.customer_service_release_gate --report offline-report.json --inputs evals/ai_cs_01e_b_release_gate_default_blocked_v1.json
```

The committed input fixture has every real switch and approval false and no
report evidence digest, so it deterministically returns `BLOCKED`.

Emit the strict schemas:

```powershell
python -m msb_agent_service.customer_service_release_gate --schema input
python -m msb_agent_service.customer_service_release_gate --schema decision
python -m msb_agent_service.customer_service_release_gate --schema observability
```

## Exclusions And Remaining Blockers

- No production-quality, latency, cost, pricing, or rollout evidence exists.
- No runtime dashboard, alert, feature flag, cohort, or rollback automation is
  created.
- No gateway, frontend, Product, Auth, OpenSearch, provider, or Agent runtime
  capability is enabled.
- No migration or environment/configuration change is required.
- All real feature and provider states remain false.

## Verification

- Combined 01E-A/01E-B evaluation and release-gate suite: 30 passed.
- Full default Agent suite: 196 passed with 14 expected opt-in
  MySQL/OpenSearch integration skips.
- Python compilation and all input, decision, and observability schema CLI
  paths passed.
- The committed default input CLI path returned `BLOCKED` as required.
- Cleanup regressions verify exact threshold, baseline-failure,
  metric/dependency, and gate-result cardinality.
- Diff/whitespace, credential-pattern, and unsafe-log checks passed.
- No network, provider, external OpenSearch, runtime, or pricing request
  occurred.
