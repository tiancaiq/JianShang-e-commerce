# Marketplace Enforcement Appeals

Status: `ADM-APL-00/01/02` and `ADM-APL-03` are implemented. Final milestone
acceptance remains pending the complete live and repository verification
matrix.

## Boundary and ownership

Auth Service owns the appeal aggregate, assignment, private review notes,
recommendation, final outcome, resolution preview, and append-only appeal
timeline. An appeal always references one stable `enforcementActionId`; target
type, target ID, appellant, original case, and enforcement version are
server-derived. Appeals never target a user, business, or listing generically.

User and business enforcement context and final mutations remain Auth-owned.
Product remains authoritative for listing enforcement and exposes both the
token-protected, allow-listed ownership/context adapter and a narrow listing
appeal-resolution adapter. There are no cross-service foreign keys or
cross-service database reads.

`UPHOLD_RECOMMENDED`, `MODIFY_RECOMMENDED`, and `REVOKE_RECOMMENDED` remain
immutable human review conclusions. They are distinct from authoritative final
states `UPHELD`, `MODIFIED`, and `REVOKED`; analytics and affected-actor views
never relabel a recommendation as a final result.

## Eligibility and submission

Only active actions are appealable. The affected user may appeal a USER action.
An active OWNER or MANAGER may appeal a BUSINESS action. An individual listing
owner or an active OWNER/MANAGER of the listing's business may appeal a LISTING
action. STAFF and unrelated actors are rejected server-side. The client cannot
supply authoritative target or appellant fields.

One appeal is permitted per enforcement action. The unique enforcement-action
key returns `409 APPEAL_ALREADY_EXISTS` for duplicates and also preserves the
no-second-level-appeal boundary. Reasons are stable enum values. `OTHER`
requires a plain-text explanation; all explanations are normalized, control
character rejected, and bounded to 2,000 characters. Arbitrary evidence URLs
and uploads are intentionally unavailable; relevant safe references may be
described in the explanation until a separately approved evidence adapter
exists.

Affected actors use:

- `GET /api/v1/enforcements/mine`
- `POST /api/v1/enforcements/{enforcementActionId}/appeals`
- `GET /api/v1/appeals/mine`

Responses include safe target labels, action/scopes/times, support references,
eligibility, and appeal status. They exclude staff identity, reporter identity,
case notes, evidence, internal reasons, and correlation IDs.

## Admin workflow

The lifecycle is:

```text
SUBMITTED -> UNDER_REVIEW
          -> UPHOLD_RECOMMENDED
          -> MODIFY_RECOMMENDED
          -> REVOKE_RECOMMENDED

UPHOLD_RECOMMENDED -> UPHELD
MODIFY_RECOMMENDED -> MODIFIED
REVOKE_RECOMMENDED -> REVOKED
```

Unassigned appeals may be claimed. Only the assigned reviewer may release,
start review, add a private note, or record a recommendation. Another reviewer
has read-only access. Every review-stage aggregate mutation requires
`expectedVersion` and stale changes return `409 APPEAL_VERSION_CONFLICT`; the
UI reloads without a silent retry. Private notes also require a bounded
idempotency key. Reusing the
same key and note body replays the original result; changing the body returns
`409 APPEAL_NOTE_IDEMPOTENCY_CONFLICT`. Recommendation content is read-only;
only the separate authorized resolution command may advance it to its matching
final state.

`MODIFY_RECOMMENDED` stores a target-compatible replacement action, scopes,
expiry, reason, and expected target version. It remains a draft until an
authorized executor completes the separate final-resolution workflow.

Admin routes are `/admin/appeals` and `/admin/appeals/:appealId`. APIs provide
server pagination, stable sort, exact appeal/enforcement/target search,
target/status/reason/assignment/date filters, claim/release, explicit review
start, append-only notes, and recommendation.

## Final resolution workflow

A final resolution is allowed only after a recommendation. The recommendation
determines the outcome; the executor cannot substitute a different one:

- `UPHELD` preserves the original enforcement action unchanged.
- `REVOKED` revokes the referenced action and then exposes any weaker or
  overlapping action that remains effective.
- `MODIFIED` revokes the referenced action and creates the stored compatible
  replacement linked to it. The original action and case remain immutable
  history.

The public admin workflow is deliberately two-step:

```text
POST /api/v1/admin/appeals/{appealId}/resolution/dry-run
POST /api/v1/admin/appeals/{appealId}/resolution
```

Dry run requires the expected appeal, original-enforcement, and target
versions. It performs no enforcement or appeal-state mutation; it stores only
the expiring confirmation record and returns the inferred final outcome,
replacement proposal when applicable, predicted effective state, remaining
effective restrictions, impact summary, warnings, and a short-lived preview
token. That token is bound to the executor and every relevant appeal, target,
original-action, replacement, and overlapping-enforcement state used by the
preview.

Execution requires the same three versions, preview token, bounded
idempotency key, and `confirmed=true`. It revalidates the bound state before
mutation. Same key and same request replay the completed result; changed use of
the key returns `409 APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT`. Stale state,
expired/invalid previews, and concurrent resolution fail without silently
re-running a new preview. A final appeal is immutable and only one executor can
win the optimistic/concurrency boundary.

USER and BUSINESS revoke-or-replace execution and the Auth appeal finalization
share one Auth transaction. LISTING revoke-or-replace execution occurs in one
Product transaction through the internal owner adapter. There is no distributed
transaction: Product persists an appeal-scoped command fingerprint and result,
so an Auth retry after Product completion replays the same owner command and
can safely finalize the appeal. Failed final-resolution attempts append a safe
`APPEAL_RESOLUTION_FAILED` audit event outside the rolled-back mutation
transaction.

## Permissions and privacy

`admin.appeal.read`, `admin.appeal.assign`, `admin.appeal.review`, and
`admin.appeal.resolve` are active. Recommendation ownership and final execution
authority are intentionally separate: an authorized executor need not be the
assigned reviewer, and the actual executor is persisted and audited. `UPHELD`
requires `admin.appeal.resolve`. `REVOKED` additionally requires the target's
reinstate permission. `MODIFIED` additionally requires that reinstate
permission and the target-compatible create permission (`restrict`, `suspend`,
or `ban`; listings use `admin.listing.suspend`). Backend authorization is
authoritative; the UI only reflects returned capabilities.

Trust & Safety retains read/assign/review, while Super Admin and the legacy
Platform Admin mapping carry `admin.appeal.resolve` subject to the additional
target-specific permissions above. Auditor remains read only, and Listing
Moderator receives none by implication.

Admin detail composes the appeal with original enforcement, original case,
linked reports, immutable report snapshot, current target state, enforcement,
case, and appeal timelines. Review notes remain admin-only. The original case
and its notes/evidence are not modified or copied into appellant-facing APIs.
After finalization, `GET /api/v1/appeals/mine` adds only `resolvedAt`, a bounded
safe outcome summary, and the current effective target state. It does not
expose the reviewer, executor, internal notes/reasons, overlap details, or
correlation data.

## Persistence and audit

Auth migration `V202608160200__create_marketplace_appeals.sql` adds `appeals`,
`appeal_replacement_scopes`, `appeal_review_notes`, and `appeal_events` plus
permission mappings. Appeals and events are never hard-deleted.

Events are `APPEAL_SUBMITTED`, `APPEAL_CLAIMED`, `APPEAL_RELEASED`,
`APPEAL_REVIEW_STARTED`, `APPEAL_NOTE_ADDED`, and the three recommendation
events. Forward Auth migration
`V202608260100__complete_marketplace_appeal_resolution.sql` activates
`admin.appeal.resolve`; adds resolution time, executor, safe summary,
idempotency/fingerprint, replacement-action link, and confirmed version fields;
adds the `(resolved_at,status,id)` analytics access path; creates short-lived
`appeal_resolution_previews`; and adds final/failure event types.

Forward Auth migration
`V202608260200__reserve_appeal_resolution_commands.sql` adds the durable,
globally unique `appeal_resolution_commands` reservation. Auth binds an
idempotency key to one appeal and request before any Product-owned mutation;
same-key/same-request retries are replayable, while cross-appeal or changed
reuse is rejected before an owner command can run.

Product migration
`V202608260100__create_listing_appeal_resolution_commands.sql` creates the
appeal-scoped, request-fingerprinted owner command ledger used to make LISTING
revoke-or-replace completion replayable. Forward Product migration
`V202608260200__allow_upheld_listing_appeal_resolutions.sql` extends that owner
ledger to state-pinned `UPHELD` commands. Product locks the listing and its full
enforcement set for all three outcomes, links a replacement action to the
original action for `MODIFIED`, and records the actual executor in its existing
enforcement events. Actor identity is server-derived. Recovery-only replay may
read an already completed owner command after an Auth rollback, but it cannot
start a new mutation from an expired or stale preview.

Successful final events are `APPEAL_UPHELD`,
`APPEAL_ENFORCEMENT_REVOKED`, or `APPEAL_ENFORCEMENT_MODIFIED`. Reads, denials,
and authorization or preflight validation/stale failures create no appeal
event. A failure inside the already-authorized owner-execution/finalization
boundary records only the normalized `APPEAL_RESOLUTION_FAILED` event after the
business transaction rolls back; it never impersonates an unauthorized caller
as a platform admin and leaves no partial Auth-owned enforcement or final
appeal mutation.

## Deferred

Second-level appeals, evidence upload, legal escalation, refunds, payout
changes, AI recommendations, and automatic decisions remain deferred. A final
appeal outcome cannot itself be appealed and does not reopen or rewrite the
original investigation case.
