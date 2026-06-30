# Admin MVP Plan

## Scope

Admin work is in product scope across releases. This plan defines the MVP admin
foundation only. Later admin capabilities are deferred admin roadmap work, not
discarded work.

MVP admin must support:

- Platform-admin authorization for the admin portal.
- Business application queue, review detail, and decisions.
- Listing moderation case queue, claim/release, review detail, and decisions.
- Decision history and minimal audit visibility for the workflows above.

MVP admin must not implement checkout, payment, order, shipping, notification,
AI, dispute, or reputation workflows. Those dependencies belong to later
release slices even when the admin portal eventually needs views for them.

## Current Baseline

Already implemented:

- Admin frontend route group protected by auth guard.
- Platform admin check through `/api/v1/admin/me`.
- Business application decision:
  `POST /api/v1/admin/business-applications/{id}/decision`.
- Basic listing moderation queue:
  `GET /api/v1/admin/listings/moderation`.
- Basic listing moderation decision:
  `POST /api/v1/admin/listings/{listingId}/decision`.
- Business application queue, detail, and versioned decision UX.
- Listing moderation case queue, claim/release, review detail, and case
  resolution through the claimed case workflow.

Known MVP gaps:

- Admin decision history is split across workflow tables and is not surfaced
  cleanly in the admin UI.
- Admin UX still needs consistent history visibility across workflows.

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
- Admin APIs verify `PLATFORM_ADMIN`.
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

## Deferred Admin Roadmap Scope

The following features are in admin product scope but are deferred until after
the MVP admin foundation is stable:

1. Reports queue, starting with user-reported listings.
2. User and business suspensions/restores.
3. Support cases and staff notes.
4. Chat evidence review with strict access auditing.
5. Payment, order, and finance admin operations.
6. Advanced trust, disputes, and safety tooling.
7. AI moderation assistance.

These later slices need their own contracts, permissions, state machines,
auditing rules, and release placement. They should not be mixed into the MVP
business approval and listing moderation implementation.

### ADM-REP-01 User-reported listing queue

Status: deferred. Reporting submission is not implemented yet, so this slice is
documentation-only until the user report flow and report persistence exist.

Goal: let platform admins see listings that users have reported, without
mixing user reports into the listing submission moderation queue.

Expected deliverables:

- Admin route for reported listings.
- Backend endpoint for listing reports, likely a filtered form of
  `GET /api/v1/admin/reports`.
- Queue rows showing report ID, reported listing ID/title, reporter metadata
  safe for staff review, report reason/category, report status, submitted time,
  assigned admin, and linked moderation/action state.
- Detail entry point from each reported listing row.

Acceptance criteria for the future slice:

- Only authorized platform staff can view reports.
- The queue shows user-reported listings separately from submitted listing
  review cases.
- Report claim/status changes are audited.
- Reported-listing actions must reuse approved admin listing actions where
  possible, such as active listing edit/removal.
- Reports do not expose unrestricted chat bodies, payment data, secrets, or
  unrelated user PII.

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
