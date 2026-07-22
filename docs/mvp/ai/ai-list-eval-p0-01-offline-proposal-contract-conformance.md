# AI-LIST-EVAL-P0-01 Offline Proposal Contract Conformance

## Status

Implemented and verified as a deterministic, offline-only evidence checkpoint.
The AI feature counter advances from `0/3` to `1/3`. This checkpoint does not
authorize runtime activation or rollout.

## Scope

This checkpoint evaluates the existing AI-LIST-01 listing-content proposal
contract without changing proposal, Product, gateway, frontend, persistence, or
runtime behavior. It uses only Agent Service-owned deterministic fixtures and
in-process fake media and vision adapters.

The versioned assets are:

- fixture schema: `ai-list-offline-evaluation-fixture-v1`
- fixture: `ai-list-eval-p0-01-fixture-v1`
- report schema: `ai-list-offline-evaluation-report-v1`
- runner: `ai-list-eval-p0-01-runner-v1`

The evaluator temporarily enables the proposal orchestrator only inside the
isolated in-process harness. It does not load runtime configuration, call the
Product media endpoint, execute a provider request, or change any capability
flag. Every release input represented in the fixture and report remains
`false`.

## Deterministic cases and metrics

The fixture covers exactly one case for each approved scenario:

- clear and ambiguous proposals
- privacy redaction
- media and instruction-text prompt injection
- protected-field claims
- cross-media evidence rejection
- idempotent replay
- fake media unavailability and timeout
- fake vision outage and timeout
- malformed fake vision output

Each report includes bounded case outcomes and these exact offline contract
metrics:

- strict schema conformance
- evidence and unknown-field consistency
- protected-field rejection
- privacy redaction
- injection resistance
- replay determinism
- dependency-failure handling
- outcome conformance

Every metric uses an exact provisional offline threshold of `1.0000` with the
`EQ` comparator. Any failed case or threshold produces baseline `FAIL`.
Baseline `PASS` means only that the current implementation conforms to this
deterministic fixture.

The report binds its inputs with a canonical fixture SHA-256 digest and has a
deterministic canonical report digest. It contains no actor ID, listing ID,
prompt, media bytes, proposal text, provider body, storage reference, or
personal data.

## Usage and latency evidence

Usage metadata is fixed and schema-validated as:

- external provider requests: `0`
- input tokens: `0`
- output tokens: `0`
- estimated cost: `0`
- pricing approved: `false`
- production-evidence eligible: `false`

Fake media and fake vision invocation counts are reported separately; they are
local harness operations and are not provider requests.

Latency is fixture-provided deterministic simulation and is always labeled
`NON_PRODUCTION_SIMULATED` with `productionSloEligible=false`. It has no
production SLO threshold and cannot be used as production latency evidence.

## Release decision contract

The machine-readable report permits only `releaseDecision=BLOCKED`. It includes
a blocker for every missing or disabled release input:

- Agent proposal API, proposal orchestration, Product media tool, provider
  execution, gateway exposure, and frontend entry
- production quality, latency, and cost evidence
- privacy and policy approval
- rollout approval

An offline regression adds `OFFLINE_BASELINE_FAILED`, but correcting that
regression still cannot produce `READY`. Production evidence and approvals
remain external unknowns; the evaluator neither measures nor fabricates them.

## Reproduction

From `agent-service`:

```powershell
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evaluation
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evaluation --digest
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evaluation --schema fixture
.\.venv\Scripts\python.exe -m msb_agent_service.listing_proposal_evaluation --schema report
```

The default command emits the strict JSON report and exits non-zero if the
offline baseline fails. `--digest` emits only the deterministic report digest.
No command writes an artifact or accesses the network.

## Verification

- focused schema, CLI, evaluator, digest, default-blocked, privacy, injection,
  replay, and fake-failure tests
- combined AI-LIST proposal/provider/review/evaluation regressions
- full default Agent Service test suite
- Python compilation and scoped credential, unsafe-log, dependency/network,
  default-off, diff, and whitespace checks

## Remaining limitations

This checkpoint supplies no production-quality, latency, cost, privacy, policy,
or rollout evidence. It does not validate a live Product media adapter, live
model provider, gateway route, frontend entry, operational telemetry, or
cohort. All runtime capabilities and release gates remain disabled and
`BLOCKED`.
