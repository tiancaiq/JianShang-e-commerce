# ADM-GOV-01/02 — Admin Governance and Sensitive-Action Approval

Status: implemented. Auth Service is the application source of truth for
effective admin authority and the approval ledger. Keycloak continues to
authenticate identities; it is not used as a second mutable admin-role store.
Governance never writes Order, Payment, Product, Inventory, or other owner
service tables.

## Identity and role assignments

An admin is a normal `users` identity with at least one currently effective
row in `admin_role_assignments`. The migration preserves existing admin access
by copying the prior `user_roles` admin assignments. Legacy
`PLATFORM_ADMIN` authority continues to present its established platform-admin
session contract while equivalent super-admin authority is preserved.

Each assignment records the user, allow-listed role, lifecycle state,
effective/expiration window, server-derived grantor, reason, revocation data,
correlation ID, idempotency fingerprints, timestamps, and optimistic version.
Rows are never hard-deleted. A temporary elevation is an assignment with
`expires_at`; it may be scheduled, active, expired, or revoked.

Authorization is calculated in SQL using:

```text
status = ACTIVE
effective_at <= current UTC time
expires_at is null or expires_at > current UTC time
```

Therefore an expired elevation stops granting permissions without a cleanup
job. `/api/v1/admin/me` uses these effective assignments and their existing
role-to-permission mappings. Historical rows do not contribute authority.

Protected service identities cannot receive human roles. Roles are allow-
listed and permissions cannot be supplied by the browser. `SUPER_ADMIN`
grant/revoke is always routed through the critical dual-approval policy.
Ordinary grants require role-management permission, and time-limited grants
also require elevation-management permission.

## Lockout and self-protection

Role mutations serialize on the singleton `ADMIN_AUTHORITY` governance state
row and use optimistic versions. Revoking an effective `SUPER_ADMIN` is
allowed only when another effective, non-expiring `SUPER_ADMIN` assignment
will remain. This conservative rule accounts for current and future windows:
a scheduled or expiring assignment is not treated as sufficient emergency
authority. The same serialized check protects concurrent revocations.

Self-revocation of an ordinary role is allowed when the normal rules pass.
Self-revocation of the final recoverable super-admin assignment is rejected.
Admin access is derived from effective assignments, so there is no redundant
admin-disable flag and normal marketplace account state is not changed.

## Admin governance views

The Angular routes are:

```text
/admin/governance
/admin/governance/admins
/admin/governance/admins/:adminId
/admin/governance/roles
/admin/governance/approvals
/admin/governance/approvals/:approvalId
```

The dashboard is a bounded operational summary, not analytics. Admin search is
server-paginated and supports safe text, role, admin-access state, and temporary
elevation filters. Detail distinguishes marketplace user state, admin-access
state, effective roles/permissions, active/scheduled assignments, historical
assignments, current temporary elevations, bounded activity, governance
timeline, and server-derived capabilities. The role catalog is read-only and
shows established role-to-permission mappings.

Activity is reference-oriented. Governance records role and sensitive-action
events plus the typed domain target/execution reference; it does not copy raw
domain payloads or perform an unbounded cross-service audit scan. Owner-service
details remain available in their own audit timelines.

Role grant/revoke dry runs persist nothing and show current/proposed roles,
permission differences, windows, warnings, approval needs, and lockout impact.

## Permissions

The canonical permissions are:

```text
admin.governance.read
admin.governance.roles.read
admin.governance.roles.manage
admin.governance.elevation.manage
admin.governance.approval.read
admin.governance.approval.request
admin.governance.approval.review
admin.governance.audit.read
```

`admin.governance.policy.manage` is reserved and has no policy-editor UI.
`SUPER_ADMIN` receives governance authority through normal role mappings.
`GOVERNANCE_ADMIN` can manage assignments and review approvals. `AUDITOR` is
read-only. Domain permissions are always checked in addition to governance
permissions.

## Approval policy and lifecycle

`sensitive_action_policies` contains typed, versioned policies with risk
`LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`; mode `NONE`, `SINGLE_APPROVAL`, or
`DUAL_APPROVAL`; required approval count; requester self-review rule; expiry;
requester and reviewer permissions; optional threshold; enablement; and
version. There is no script language and no execute-arbitrary-command API.

Initial policies are:

| Action | Risk/mode | Threshold | Domain authority |
| --- | --- | --- | --- |
| Grant `SUPER_ADMIN` | Critical, two independent approvals | none | governance role manager |
| Revoke `SUPER_ADMIN` | Critical, two independent approvals | none | governance role manager |
| Large refund | High, one independent approval | `1000.00 USD` seed policy | `admin.refund.execute` |
| High-impact category disable | High, one independent approval | `1000` active listings seed policy | `admin.catalog.policy.manage` |

Thresholds are database policy configuration, not frontend constants. The
seed values are conservative local/demo defaults and must be reviewed as
controlled operational configuration before production rollout. Advanced
policy editing remains deferred.

An approval stores only a safe summary, allow-listed typed payload,
SHA-256 canonical payload fingerprint, target reference, policy/target
versions, status, expiry, approval count, execution/failure reference,
correlation ID, and optimistic version. Decisions are append-only. A unique
request/approver constraint prevents one reviewer from counting twice.

Lifecycle:

```text
PENDING -> APPROVED -> EXECUTING -> EXECUTED
        -> REJECTED
        -> CANCELLED (requester only)
        -> EXPIRED (derived from the time boundary)
APPROVED/FAILED -> INVALIDATED when revalidation fails
EXECUTING -> FAILED when the typed owner command fails
```

The requester cannot approve or reject when `requester_may_approve=false`.
Dual approval requires two distinct authorized reviewers. Reviewer permission
does not grant domain execution authority. The executor must still hold the
policy's original requester/domain permission.

Before execution Auth rechecks approval expiry, policy enablement/version,
requester/executor authority, target version and current owner-service state,
and the canonical payload fingerprint. Stale financial capacity, catalog
impact/version, role state, or policy state invalidates the request and no
owner mutation occurs. `EXECUTED` is recorded only after the typed domain
adapter reports success/acceptance; failures remain explicit and retry-safe.

## Typed owner-service integration

Large refunds enter governance only after Payment performs its existing dry
run and normal `admin.refund.execute` check. Below-threshold refunds use the
existing Payment command directly. Above-threshold submissions return HTTP
`202` with a governance approval reference and do not call the provider or
write a refund. After approval, Auth revalidates Payment's current preview and
calls Payment's internal governed-refund endpoint with the executor token and
a stable governance idempotency key. Payment retains the row lock, cumulative
refund ceiling, provider, refund, and outbox ownership.

Category disablement similarly reuses Product's existing impact preview.
Below-threshold changes execute normally; high-impact changes return HTTP
`202`. Execution revalidates counts and category version before calling the
typed Product command. Existing active listings remain unchanged according to
the catalog lifecycle contract.

No production-ready high-impact ADM-SYS mutation matched this milestone's
policy boundary. Existing bounded one-item retries/reindex remain directly
permissioned and idempotent. Full reindex, dangerous terminal replay, and
runtime feature mutation remain unavailable, so no fake system governance
target was introduced.

## Concurrency, idempotency, and audit

Role grants, role revocations, approval requests, decisions, cancellations,
and executions use actor-scoped keys plus request fingerprints. Exact retries
replay the prior result; changed content returns `409`. Approval and assignment
versions reject stale writes. Database uniqueness protects concurrent request
and decision insertion. The governance-state row serializes authority changes,
and Payment/Product retain their owner-level locks and versions. Stable
governance-derived command keys ensure a retry cannot create a second refund
or catalog mutation.

Append-only `admin_governance_events` records the server-derived actor,
requester/subject where relevant, approval, typed target, reason, outcome,
allow-listed metadata, correlation ID, and timestamp. It includes role request,
grant/revoke/elevation and approval requested/reviewed/cancelled/invalidated/
execution events. Secrets, raw provider responses, payment credentials, exact
addresses, and arbitrary browser payloads are not stored.

## Bootstrap, recovery, and deferred work

Migrations backfill existing admin authority and never delete it. Normal APIs
cannot remove the final recoverable super admin. There is no secret or
unauthenticated recovery endpoint. If authority is lost through external data
damage, an engineer must use an audited maintenance window to restore a known
human user's `SUPER_ADMIN` assignment directly in the Auth database, validate
`/api/v1/admin/me`, and record the incident/change reference. Keycloak alone
must not be treated as restoring application authority.

ADM-ANL-01 reads only the limited governance dashboard signals when the actor
also has `admin.governance.read`: pending/expired approvals, failed execution,
and active temporary-elevation counts. It does not expose role-change detail or
execute a governed action. See [admin-analytics.md](admin-analytics.md).

Deferred: free-form role creation, role/permission editing, policy editor,
generic workflow scripting, payouts, legal/HR identity management, high-impact
system commands that do not yet exist, and all AI.
