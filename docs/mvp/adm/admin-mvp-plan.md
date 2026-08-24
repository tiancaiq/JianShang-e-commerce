# Admin MVP Plan

## Scope

Admin work is in product scope across releases. This plan defines the MVP admin
foundation only. Later admin capabilities are deferred admin roadmap work, not
discarded work.

MVP admin must support:

- Fine-grained, application-owned authorization for the admin portal.
- Business application queue, review detail, and decisions.
- Listing moderation case queue, claim/release, review detail, and decisions.
- Decision history and minimal audit visibility for the workflows above.

MVP admin must not implement checkout, payment, order, shipping, notification,
AI, dispute, or reputation workflows. Those dependencies belong to later
release slices even when the admin portal eventually needs views for them.

## Current Baseline

Already implemented:

- Admin frontend route group protected by auth guard.
- Effective admin roles and permissions through `/api/v1/admin/me`.
- Business application decision:
  `POST /api/v1/admin/business-applications/{id}/decision`.
- Basic listing moderation queue:
  `GET /api/v1/admin/listings/moderation`.
- Basic listing moderation decision:
  `POST /api/v1/admin/listings/{listingId}/decision`.
- Business application queue, detail, and versioned decision UX.
- Listing moderation case queue, claim/release, review detail, and case
  resolution through the claimed case workflow.
- Normalized chronological audit timelines on business application and listing
  moderation detail pages.
- Explicit listing case ownership capabilities that make cases assigned to
  another admin, and resolved cases, read-only before an action is attempted.

`ADM-MVP-RC-01` completes the approved MVP workflows and `ADM-SEC-01`
completes their fine-grained authorization foundation. `ADM-ENF-00`,
`ADM-USER-01/02`, `ADM-BUS-04/05`, and `ADM-LIST-06` add reversible user,
business, and listing enforcement with authoritative runtime checks. Search
Maintenance remains ongoing, feature-gated work outside this release train.
Category Guidance remains disabled by default. Reports, investigation cases,
appeals, disputes, financial administration, payout enforcement, and AI
automation remain deferred.

Cleanup verification status:

| Milestone | Status |
|---|---|
| `ADM-MVP-RC-01` | Complete |
| `ADM-SEC-01` | Complete |
| `ADM-ENF-00` | Complete |
| `ADM-USER-01/02` | Complete |
| `ADM-BUS-04/05` | Complete |
| `ADM-LIST-06` | Complete |

## MVP Slices

### ADM-00 Admin portal shell hardening

Goal: make `/admin` a dependable workspace for platform staff.

Deliverables:

- Admin landing page with counts for pending business applications, pending
  listing moderation cases, and cases assigned to the current admin.
- Clear unauthorized, loading, empty, and error states.
- Admin navigation limited to MVP workflows.
- Backend authorization remains enforced by each admin API.

Acceptance criteria:

- Non-admin users cannot load protected admin data.
- Admin APIs verify the permission required by the requested operation.
- Admin action requests carry actor context and correlation ID.

### ADM-BUS-01 Business application queue

Goal: replace manual application ID lookup with a review queue.

Deliverables:

- `GET /api/v1/admin/business-applications`
- Optional status filter for `PENDING_VERIFICATION` or `UNDER_REVIEW`;
  default ordering is oldest submitted application first.
- Queue row fields: application ID, legal name, status, applicant metadata safe
  for admin review, submitted time, decided time, and version.
- Frontend queue with status filters. Clickable row detail is handled by
  ADM-BUS-02.

Acceptance criteria:

- Only platform admins can read the queue.
- Results include submitted applications that need admin attention.
- Queue reads do not expose secrets, tokens, or unrelated user PII.

### ADM-BUS-02 Business application review detail

Goal: let admins inspect a submitted application safely from one page.

Deliverables:

- `GET /api/v1/admin/business-applications/{id}`
- Detail page for application data, status, version, reviewer metadata,
  decision reason, and approved business ID.
- Queue rows link to the detail page.

Acceptance criteria:

- Only platform admins can read the detail page.
- Non-owner admins can read review context without using applicant-owned
  endpoints.

### ADM-BUS-03 Business application decision UX

Goal: let admins decide applications from the detail page.

Deliverables:

- Decision form for `APPROVE`, `REJECT`, and `REQUEST_INFORMATION`.
- Stale-version handling for decisions.

Acceptance criteria:

- Decision reason is required.
- Approval creates the business row and active owner membership exactly once.
- Every decision appends a business verification event.
- Concurrent decision attempts fail cleanly.

### ADM-LIST-00 Listing moderation case foundation

Goal: introduce case workflow state for listing review without adding report,
support, suspension, or dispute flows.

Deliverables:

- Forward-safe Flyway migration for `moderation_cases` in the catalog schema.
- Listing-only case type: `LISTING_REVIEW`.
- Fields: ID, subject listing ID, status, priority, assigned admin user ID,
  version, created time, updated time, resolved time.
- Indexes for open cases and assigned cases.

Acceptance criteria:

- Case status values are explicit and stable.
- Case version supports optimistic locking.
- Listing state remains authoritative for listing publication/moderation
  outcome.

### ADM-LIST-01 Listing submission creates a moderation case

Goal: create review work automatically when a listing is submitted.

Deliverables:

- `POST /api/v1/listings/{listingId}/submit` creates or reuses one open
  listing moderation case.
- Duplicate open cases for the same listing are prevented.
- Existing listing submit rules remain unchanged.

Acceptance criteria:

- Submitting a valid draft moves listing moderation state to pending and
  creates an open case.
- Retrying submit for the same pending listing does not duplicate the case.
- Unauthorized listing owners cannot create cases for listings they do not
  own.

### ADM-LIST-02 Listing moderation queue upgrade

Goal: let admins work from a case-backed queue.

Deliverables:

- `GET /api/v1/admin/moderation/listing-cases`
- `POST /api/v1/admin/moderation/listing-cases/{caseId}/claim`
- `POST /api/v1/admin/moderation/listing-cases/{caseId}/release`
- Queue filters for open, unassigned, assigned to me, and resolved cases.
- Frontend queue showing case status, assignment, listing summary, seller type,
  submitted time, and priority.

Acceptance criteria:

- Only platform admins can claim or release cases.
- Claimed cases record assigned admin user ID.
- Concurrent claim/release attempts are rejected with a stale-version response.

### ADM-LIST-03 Listing review detail and resolution

Goal: resolve a listing moderation case and apply the listing decision in one
audited admin action.

Deliverables:

- Listing case detail page with listing fields, images, media status, current
  listing version, case status, assigned admin, and decision history.
- Resolve actions: `APPROVE`, `REJECT`, `REQUEST_CHANGES`.
- Resolution updates the listing through the existing moderation decision
  rules and closes the moderation case.

Acceptance criteria:

- Reason is required for every resolution.
- Only pending-review listings can be resolved.
- The case must be claimed by the current admin before resolution.
- Resolved cases are read-only in the admin detail view.
- Attached image/media moderation status follows the listing decision.
- Case resolution and listing decision are atomic.
- Concurrent resolution attempts are prevented.

### ADM-LIST-04 Active listing admin edit and removal

Goal: let platform admins correct or remove live listings after approval
without deleting listing history.

Deliverables:

- `GET /api/v1/admin/listings/{listingId}`
- `PATCH /api/v1/admin/listings/{listingId}`
- `POST /api/v1/admin/listings/{listingId}/remove`
- Active listing actions on the listing review detail page when the listing is
  `ACTIVE / APPROVED`.

Acceptance criteria:

- Only platform admins can edit or remove active approved listings.
- Every edit/remove requires `If-Match` with the current listing version.
- Every edit/remove requires a reason and records listing moderation history.
- Admin removal sets listing status to `REMOVED_BY_ADMIN` and removes it from
  public marketplace reads; it does not hard-delete database rows.
- Non-active listings cannot use active listing admin actions.

### ADM-LIST-05 Listing review search

Goal: help platform admins find listing review cases without adding a separate
search service dependency to the MVP admin path.

Deliverables:

- Optional `q` query parameter on
  `GET /api/v1/admin/moderation/listing-cases`.
- Search field on the listing review queue that preserves the current filter.
- Clear search action that reloads the current filter without `q`.

Acceptance criteria:

- Empty search preserves existing queue behavior.
- Erasing an active search reloads the current filter without requiring another
  Search click.
- Search applies after the selected filter, so Open, Unassigned, Assigned to
  me, and Resolved still return only cases in that filter.
- MVP search covers case ID, listing ID, listing title, seller/submitted-by
  IDs, assigned admin ID, and SKU.
- Display-name search is deferred until auth-service exposes a stable identity
  search contract.

### ADM-AUD-01 Minimal admin audit visibility

Status: complete as part of `ADM-MVP-RC-01`.

Goal: show enough history for admins to understand decisions made in MVP
workflows.

Deliverables:

- Business application detail shows business verification events.
- Listing review detail shows listing moderation decisions and case assignment
  history.
- Audit data is exposed only through workflow detail pages.

Acceptance criteria:

- Audit views include actor, action, reason when applicable, subject, timestamp,
  and correlation ID when available.
- Audit views do not include passwords, tokens, payment details, unrestricted
  chat bodies, or unrelated PII.

Implementation notes:

- Auth Service owns the business application timeline at
  `GET /api/v1/admin/business-applications/{id}/timeline`.
- Product Service owns the listing case timeline at
  `GET /api/v1/admin/moderation/listing-cases/{caseId}/timeline`.
- Both endpoints use the normalized admin timeline response contract while
  retaining service-local persistence and authorization.
- Listing case creation, reopen, claim, release, reassignment through a later
  claim, resolution, active edit, and active removal are retained as immutable
  audit entries when applicable.
- Actor identity comes from the authenticated backend principal. Client actor
  or reviewer identifiers are never accepted.

## ADM-MVP-RC-01 Release Candidate

Status: complete.

Release-candidate coverage includes:

- Complete business submission/provider/admin-decision timeline rendering.
- Complete listing case creation, claim, release/reassignment, decision,
  resolution, active edit, and active removal timeline rendering.
- Reviewer display values and correlation IDs where available.
- Explicit `canClaim`, `canRelease`, `canResolve`, `isReadOnly`, and
  `readOnlyReason` frontend capabilities.
- Explicit `403` messages and `409` state reloads without silently retrying a
  moderation decision.
- Playwright coverage in `frontend/e2e/admin-release-candidate.spec.ts` for
  admin access, business approval, listing claim/resolution, a second admin's
  read-only view, and audit visibility.

The Playwright fixture uses the repository's existing local Keycloak/MySQL
setup and requires the Docker-backed application stack to be running. The
release-candidate browser workflow passed against that stack on 2026-08-12.

## ADM-SEC-01 Fine-grained admin authorization

Status: complete.

Auth Service application persistence is the source of truth for admin role
assignments and role-to-permission mappings. Keycloak authenticates the user
and supplies the stable subject used to find the application user; Keycloak
realm roles are not a second admin-authorization source. Existing
`PLATFORM_ADMIN` assignments are copied to `SUPER_ADMIN` during migration and
remain recognized during the compatibility period.

The initial roles are `SUPER_ADMIN`, `TRUST_AND_SAFETY_ADMIN`,
`BUSINESS_REVIEWER`, `LISTING_MODERATOR`, `SUPPORT_ADMIN`, `AUDITOR`, and the
unassigned reserved role `AI_ADMIN_AGENT`. `ADM-USER-01/02` additionally adds
the least-privileged `USER_RESTRICTOR` role for user read, restrict, and
reinstate operations without suspend, ban, or PII access. `SUPER_ADMIN` has every registered
permission. The focused reviewer and moderator roles have only the read and
command permissions needed by their current workflows; `AUDITOR` is read-only.
`AI_ADMIN_AGENT` has no permissions, is not assigned, and does not enable AI
execution or automatic actions.

Current workflow permissions are:

- `admin.dashboard.read` and `admin.audit.read`.
- `admin.business.application.read` and
  `admin.business.application.decide`.
- `admin.listing.moderation.read`, `admin.listing.moderation.claim`, and
  `admin.listing.moderation.resolve`.
- `admin.listing.edit` and `admin.listing.remove`.

User, business, and listing-enforcement identifiers are activated by their
target vertical migrations. Report and role-management identifiers remain
reserved; registration of those identifiers does not implement their workflows.

`GET /api/v1/admin/me` returns the application user ID, effective roles,
effective permissions, and account state. Backend services enforce the
specific permission before every existing business/listing admin operation;
listing-case ownership, resolved-state, authenticated actor, and optimistic
version checks remain additional mandatory constraints. Angular uses the same
effective session to filter navigation, guard routes, and suppress unavailable
commands, but frontend checks are not an authorization boundary.

Role-assignment UI/API, reports, financial operations, payout enforcement, and
AI automation remain explicitly deferred. User, business, and listing
enforcement are complete target-specific workflows.

## ADM-ENF-00 Shared enforcement foundation

Status: complete. `ADM-USER-01/02`, `ADM-BUS-04/05`, and `ADM-LIST-06` expose
the target-specific production workflows and runtime effects built on this
foundation.

Auth Service now owns internal enforcement records for users and businesses;
Product Service owns the equivalent records for listings. Both foundations
provide explicit capability scopes, reversible lifecycle history, durable
idempotency, target/action optimistic concurrency, effective-restriction
evaluation, ADM-SEC-01 authorization, dry-run validation, and normalized
timeline mapping. The foundation initially exposed no production mutation
controller or admin page; the completed target verticals now expose only their
owned, runtime-effective commands.

The model and runtime boundary are defined in
`docs/mvp/adm/admin-enforcement-model.md`. Existing listing moderation and
`REMOVED_BY_ADMIN` are unchanged. Keycloak disabling, login/messaging/payout
effects, cascades, notifications, appeals, and AI execution remain outside the
foundation. The three target integrations connect only their documented
operational scopes and preserve independent user, business, and listing state.

## ADM-USER-01/02 User administration and runtime enforcement

Status: implemented and focused verification complete.

Auth Service owns privacy-safe, paginated user search; user detail; active and
historical actions; normalized enforcement timeline; create/revoke dry runs;
confirmed commands; the authenticated user's safe capability summary; and the
service-token-authenticated capability decision endpoint. Full email and email
search require `admin.user.pii.read`. Read, audit, restrict, suspend, ban, and
reinstate permissions are checked independently.

`USER_BUYING` and `USER_SELLING` are operational. `USER_LOGIN` and
`USER_MESSAGING` remain reserved and are not selectable. A marketplace ban is
not identity deletion: Auth expands it to both operational scopes and stores
those explicit scopes. Revocation targets one action and may reveal a weaker
still-active action.

Order Service checks buying capability before inventory reservation and before
payment-intent creation. Product/Auth check selling capability before seller
activation and listing mutation, including actions performed by a business
staff member. Product also checks the individual listing owner's selling
capability before buyer-confirmed trade completion. Existing orders, listings,
business membership, Keycloak authentication, and other entities are not
mutated by these actions. Decision unavailability fails protected writes closed
with `503`; an active restriction returns `403`.

Angular adds `/admin/users` and `/admin/users/:userId`, guarded by
`admin.user.read`, with filters, a capability ledger, action/reinstatement dry
runs, explicit confirmation, protected-account explanations, and timeline
refresh. See `docs/mvp/adm/admin-user-control.md`.

## Deferred Admin Roadmap Scope

The following features are in admin product scope but are deferred until after
the MVP admin foundation is stable:

1. Investigation cases, appeals, and support notes.
2. Chat evidence review with strict access auditing.
3. Payment, refund, payout, and broader finance admin operations; order
   search/detail and bounded cancellation are delivered by ADM-ORD-01/02.
4. Advanced trust, disputes, and safety tooling.
5. AI moderation assistance.

These later slices need their own contracts, permissions, state machines,
auditing rules, and release placement. They should not be mixed into the MVP
business approval and listing moderation implementation.

### ADM-REP-00/01/02 Marketplace reporting and admin triage

Status: implemented. Auth Service owns cross-target report persistence;
authenticated users can report supported users, businesses, and listings;
admins have a separate permission-gated inbox, assignment, immutable snapshot
versus live state detail, related reports, read-only enforcement context, and
audited dismiss/ready/severity triage. Reporting remains separate from listing
submission moderation and cannot execute enforcement. See
[admin-reporting.md](admin-reporting.md).

### ADM-REP-03 Investigation cases

Status: implemented. Auth Service owns versioned investigation cases, report
and target links, append-only private notes, validated internal evidence
references, assignment, conclusion, and a normalized timeline. Admin routes are
`/admin/cases` and `/admin/cases/:caseId`. `READY_FOR_ACTION` and
`CLOSED_NO_ACTION` is terminal and creates no enforcement. See
[admin-investigation-cases.md](admin-investigation-cases.md).

### ADM-REP-04 Case-linked enforcement

Status: implemented. `READY_FOR_ACTION` cases hold explicit linked-target
proposals. Each proposal uses the existing target dry run, requires an exact
human confirmation, composes case and target permissions, pins one idempotency
key, records partial failure without compensation, and links the real action
ID. Resolved plans close as read-only `CLOSED_ACTIONED`. See
[admin-case-enforcement.md](admin-case-enforcement.md).

`ADM-APL-01/02` appeals is the recommended next milestone.
User-profile UI reporting is deferred until an
appropriate public profile exists; the authenticated API already supports the
`USER` target.

## Build Order

1. ADM-00 admin shell hardening.
2. ADM-BUS-01 business application queue.
3. ADM-BUS-02 business application review detail.
4. ADM-BUS-03 business application decision UX.
5. ADM-LIST-00 listing moderation case foundation.
6. ADM-LIST-01 listing submission creates a moderation case.
7. ADM-LIST-02 listing moderation queue upgrade.
8. ADM-LIST-03 listing review detail and resolution.
9. ADM-LIST-04 active listing admin edit and removal.
10. ADM-LIST-05 listing review search.
11. ADM-AUD-01 minimal admin audit visibility.

## Test Expectations

Each slice must include:

- Unit tests for domain rules and state transitions.
- Integration tests for persistence and API behavior.
- Authorization tests for non-admin access.
- Tenant and cross-user isolation tests where user/business data is visible.
- Optimistic-locking tests for claim, release, and resolve actions.
- Frontend component and service tests for visible admin behavior.

End-to-end coverage should validate:

- Submitted business application to admin decision.
- Submitted listing to case creation, claim, and resolution.
- Stale admin action handling.
- Unauthorized user blocked from admin data.

## ADM-BUS-04/05 implementation note (2026-08-15)

Active-business search/detail and reversible business marketplace enforcement
are implemented. The full new-sales and multi-member user-versus-business
Playwright workflows pass. The operational and safety contract is documented
in [admin-business-control.md](admin-business-control.md).

## ADM-LIST-06 release boundary

Reversible listing enforcement is implemented as a Product-owned policy ledger on the existing listing moderation detail. It supports `RESTRICT` and `SUSPEND`, explicit visibility/purchasability scopes, mandatory dry-run confirmation, versioned/idempotent commits, specific-action reinstatement, and audit history. It is separate from moderation decisions and `REMOVED_BY_ADMIN`.

The complete Docker/Playwright matrix in
[admin-listing-enforcement.md](admin-listing-enforcement.md) passes. Final
cleanup delivery remains gated only on the complete post-cleanup repository,
backend, frontend, and browser verification matrix. `ADM-REP-00/01/02` is now
implemented. `ADM-REP-03` investigation cases and `ADM-REP-04` case-linked
enforcement are also implemented. `ADM-APL-00/01/02` appeal foundation,
affected-actor submission, admin assignment, private review, and non-executing
recommendations are implemented in [admin-appeals.md](admin-appeals.md).
`ADM-APL-03` now adds preview-bound, explicitly confirmed, idempotent final
UPHELD/MODIFIED/REVOKED resolution through each enforcement owner. Its final
acceptance remains pending the complete live and repository verification
matrix.

## ADM-ORD-01/02 order operations

Status: implemented. Order Service now owns permission-gated, server-paginated
admin order search, a purchase-time administrative detail read model, safe
Payment/Inventory/Product/Auth enrichment, normalized order history, and a
narrow dry-run/confirm cancellation command. Cancellation reuses the existing
paid-order compensation workflow and is blocked before mutation when refund or
restock safety is unavailable. See [admin-order-operations.md](admin-order-operations.md).

The subsequent control-plane sequence is:

```text
ADM-DSP-00/01/02
→ ADM-FIN-00/01/02
→ ADM-CAT-01/02
→ ADM-PAY-03 when payouts exist
→ ADM-SYS-01/02
→ ADM-GOV-01/02
→ ADM-APL-03
→ ADM-ANL-01
→ ADM-AI-* later
```

None of those future milestones is implemented by ADM-ORD-01/02.

## ADM-DSP-00/01/02 transaction disputes

Status: implemented. Order Service owns a business-group-scoped dispute
aggregate with participant statements, bounded evidence references, admin-only
notes, claim/release ownership, information requests, priority, normalized
audit history, and immutable no-action, return-approved, or refund-recommended
decisions. Refund recommendations move no money and create no enforcement.
See [admin-disputes.md](admin-disputes.md).

## ADM-FIN-00/01/02 payment and refund administration

Status: implemented. Payment Service owns permission-gated, server-paginated payment and refund search/detail plus versioned, actor-idempotent full and partial refund dry-run/confirmation. Execution revalidates the cumulative refund ceiling across cancellation, return, and admin sources under a payment lock. Order supplies bounded context and Auth supplies safe labels and permissions. See [admin-financial-operations.md](admin-financial-operations.md).

## ADM-SUP-00/01 support operations

Status: implemented; final acceptance awaits a green repository-wide Playwright
run across the mixed live-fixture and route-mocked suites. Auth Service owns requester-safe support tickets,
participant messages, private notes, a filterable admin inbox, claim/release,
priority, validated cross-domain links, explicit handoffs, resolution,
optimistic locking, idempotency, and audit history. Owner services expose only
bounded read validation; Support cannot mutate orders, disputes, finance,
Trust & Safety, or enforcement. See
[admin-support-operations.md](admin-support-operations.md).

`ADM-CAT-01/02` is complete. The complete backend, Angular, production-build,
and Playwright acceptance gates passed. Product Service owns governed hierarchy/lifecycle, seller
eligibility, typed attributes/options, seller guidance, immutable rule
versions, listing validation, impact previews, idempotency, and audit. Angular
routes are `/admin/catalog` and `/admin/catalog/categories/:categoryId`. See
[admin-catalog-governance.md](admin-catalog-governance.md).

## ADM-SYS-01/02 marketplace and system operations

Status: complete. The complete repository, Angular, production-build, and
Playwright verification matrix passed. Auth provides the permissioned
aggregation/audit facade, while Product, Order, Payment, and Inventory retain
ownership of their operational state. The console exposes safe health, durable
work, redacted outbox, finance reconciliation, expired-reservation, search,
feature/configuration, and recovery-audit views. Only existing idempotent worker
mechanisms and bounded one-listing reindex are actionable. See
[admin-system-operations.md](admin-system-operations.md).

## ADM-GOV-01/02 admin governance

Status: complete. Auth now owns time-aware, historical admin role assignments,
temporary elevation, lockout-safe role changes, bounded governance activity,
typed sensitive-action policies, independent review, dual approval, stale
revalidation, and explicit execution. Large refunds and high-impact category
disablement reuse their Payment/Product owner commands; no generic mutation
engine exists. See [admin-governance.md](admin-governance.md).

## ADM-ANL-01 admin analytics and operational insights

Status: implemented, including authoritative final appeal outcomes and the
appeal-adjustment rate; final acceptance remains pending the complete live and
repository verification matrix. Auth owns a permissioned read-only facade;
Product, Order, Payment, and ADM-SYS retain their source data and expose bounded
aggregate reads. UTC half-open ranges, equal-duration comparisons,
finance/system/governance visibility, partial failure, typed drill-downs,
accessible trends, and no-mutation/privacy rules are defined in
[admin-analytics.md](admin-analytics.md). Recommendation and pending appeal
states are excluded from finalized counts and from
`(MODIFIED + REVOKED) / finalized * 100`.

Payouts/chargebacks and AI remain later work.
