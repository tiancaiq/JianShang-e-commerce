# PRESEARCH-STAB-P1-02 Listing Lifecycle Regression Test Cleanup

Status: complete.

## Goal

Make completed listing lifecycle rules explicit in tests before search adds
more listing entry points.

This cleanup adds or reorganizes regression tests without changing behavior.

## Scope

Review completed listing rules for:

- draft creation
- draft editing
- pending review editing
- active listing editing
- closed listing editing
- submit-for-review eligibility
- image requirements
- owner authorization

## Tasks

- Organize lifecycle tests around listing status and allowed actions.
- Add missing regression tests for already-completed behavior.
- Keep API request/response shapes unchanged.
- Keep listing status transitions unchanged.
- If a test exposes a real production bug, split the behavior fix into a
  separate bugfix PR.

## Non-Goals

- No new listing statuses.
- No new moderation workflow.
- No search behavior.
- No schema changes.

## Verification

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --include=src/app/features/listings/listing-draft-form.component.spec.ts
```

## Acceptance Criteria

- Completed lifecycle rules have clear regression coverage.
- No production behavior changes are included.

## Completion Notes

- Added frontend regression coverage for seller-entered individual quantity and
  pending-review edit-before-resubmit behavior.
- Added backend regression coverage for pending-review edit back to draft and
  pending-review close behavior.
- Documented current backend close behavior as-is: closing a pending-review
  listing moves `status` to `CLOSED` while preserving the existing
  `moderationStatus`.
- No production code, API contract, route, or schema changes were made.

## Verification Results

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/features/listings/listing-draft-form.component.spec.ts --include=src/app/features/listings/listing-draft-form.helpers.spec.ts
```

Result: `TOTAL: 27 SUCCESS`.

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Result: `Tests run: 80, Failures: 0, Errors: 0, Skipped: 0`.
