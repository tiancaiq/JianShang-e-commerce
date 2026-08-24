# Admin Case-Linked Enforcement

Status: `ADM-REP-04` implemented on 2026-08-16.

## Boundary and lifecycle

Auth Service owns the investigation case, its enforcement proposals, and the case-to-enforcement links. It does not own or recreate enforcement behavior. USER and BUSINESS proposals dispatch to the existing Auth enforcement services; LISTING proposals dispatch to the existing Product enforcement service through a commerce-token-protected internal endpoint that also forwards the current admin bearer token. Product therefore performs its own listing permission check and records the human execution actor. There is no cross-service foreign key or distributed transaction.

The operational flow is:

```text
READY_FOR_ACTION -> DRAFT -> VALIDATED -> EXECUTED|FAILED|CANCELLED
READY_FOR_ACTION -> CLOSED_ACTIONED
```

Proposal execution is synchronous, so there is no cosmetic `EXECUTING` state. A failed proposal can be retried with the same pinned execution idempotency key or explicitly cancelled/waived. Successful proposals are never rolled back automatically when another proposal fails. `CLOSED_ACTIONED` requires at least one `EXECUTED` proposal and every proposal to be `EXECUTED` or `CANCELLED`; closure creates no enforcement.

## Proposal and linkage model

`case_enforcement_proposals` stores the linked target, existing action type/scopes, reason, timing, expected target version, lifecycle state, creator, optimistic version, dry-run timestamp/version/result, pinned execution key, safe failure code/summary, resulting action ID, and correlation ID. `case_enforcement_proposal_scopes` normalizes scopes. `case_enforcement_links` preserves the case/proposal/target/action relationship, execution time, human executor, and correlation ID. Enforcement tables remain authoritative for current action lifecycle; later expiry or revocation does not rewrite the closed case.

Only USER, BUSINESS, or LISTING targets already present in `investigation_case_targets` are accepted. USER and BUSINESS reuse their operational scopes and `RESTRICT|SUSPEND|BAN`; listing reuses `RESTRICT|SUSPEND` and visibility/purchasability scopes. Listing `BAN`, payout freezes, cascades, deletion, Keycloak disabling, refunds, and order cancellation are not introduced.

## Permissions, validation, and concurrency

Plan creation, editing, cancellation, dry run, execution, and closure require
`admin.report.resolve` and assignment to the current investigator. Dry-run and
execution additionally require the target action permission (`admin.user.*`,
`admin.business.*`, or `admin.listing.suspend`). Existing
self/service/admin-account protections and target validation remain
authoritative in the target enforcement service.

Every proposal must pass the target-specific dry run. The proposal records a
technical preview cache (`dryRunValidatedAt`, `dryRunTargetVersion`, and bounded
impact) so execution can reject a stale confirmation; material edits clear that
cache and return the proposal to `DRAFT`. Preview does not change the case
version, append a domain timeline event, consume the execution idempotency key,
or mutate the enforcement target. Execution accepts only case version,
proposal version, and a stable idempotency key—the validated proposal is
authoritative. The key is pinned before target dispatch. An uncertain Product
outcome is retried with that same key, allowing the Product enforcement
idempotency ledger to return the original action rather than create a duplicate.
Case and proposal compare-and-set versions reject concurrent edits or
executions.

## Failure and audit behavior

Each proposal executes independently. A success stores the real enforcement ID, writes `case_enforcement_links`, and emits `ENFORCEMENT_EXECUTED`; the ordinary enforcement `CREATED` event still appears in the target timeline with `caseId`. A safe failure stores `FAILED`, a stable error code, and a bounded summary, and emits `ENFORCEMENT_EXECUTION_FAILED`; raw stack traces are never stored. Case timeline events cover proposal creation/update/cancellation, execution start/retry/success/failure, and actioned closure. Dry runs, authorization denials, and ordinary pre-mutation validation failures are not audited as domain events.

## Admin experience

The case detail exposes an Enforcement Plan only for `READY_FOR_ACTION` and historical `CLOSED_ACTIONED` cases. Each card shows target, action, scopes, expiration, validation state, predicted impact, permission state, independent failure/result, and exact enforcement ID. Human confirmation names the exact target, capabilities, expiration, reason, and impact. Existing active enforcement remains in the separate current-target context; resulting enforcement is shown from dedicated case links.

## Deferred

Appeal foundation, submission, recommendation review, and appeal-linked final
resolution are implemented in [admin-appeals.md](admin-appeals.md). Final
resolution reuses target-owner enforcement boundaries; the original closed
case remains historical. AI plan generation, recommendations, severity
decisions, dry-run approval, and execution remain absent. Any future admin AI
agent must use these same permissions, validations, proposal lifecycle,
idempotency, and explicit human-confirmation boundary unless a separately
approved contract changes it.
