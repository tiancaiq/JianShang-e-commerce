# PRESEARCH-STAB-P1-01 Listing Frontend Component Responsibility Cleanup

Status: complete.

## Goal

Reduce frontend listing component complexity before search UI work builds on
the same screens.

This cleanup is a no-behavior refactor.

## Scope

Review completed frontend listing surfaces:

- seller listing management
- listing draft/edit form
- public marketplace listing cards
- public listing detail
- shared listing image gallery

## Tasks

- Extract pure helpers only where existing component logic is already crowded.
- Extract small presentational pieces only where duplication is obvious.
- Preserve component inputs, outputs, routes, visible text, API calls, and
  lifecycle behavior.
- Avoid creating a broad design system.

## Changes

- Added `public-listing-display` pure helper functions for repeated public
  listing display formatting:
  - owner label fallback
  - public location label fallback
  - condition label formatting
  - primary image URL resolution
- Added unit tests for the helper functions.
- Updated marketplace home, business stores, and public listing detail
  components to delegate repeated display formatting to the helper.

## Files Changed

- `frontend/src/app/shared/listing/public-listing-display.ts`
- `frontend/src/app/shared/listing/public-listing-display.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/stores/business-stores.component.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.ts`
- `docs/mvp/fix/presearch-stab-p1-01-listing-frontend-component-responsibility-cleanup.md`
- `docs/mvp/fix/fix-04-pre-search-stabilization-sprint.md`

## Behavior

No route, visible text, API call, listing lifecycle, search, or business store
behavior changed.

## Non-Goals

- No new UI states.
- No visual redesign.
- No API changes.
- No new search behavior.
- No business store feature work.

## Verification

```powershell
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

Focused helper/component tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/shared/listing/public-listing-display.spec.ts --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/features/marketplace/public-listing-detail.component.spec.ts
```

Result:

```text
TOTAL: 14 SUCCESS
```

Full frontend tests:

```powershell
cd frontend
npm.cmd test -- --watch=false
```

Result:

```text
TOTAL: 192 SUCCESS
```

Frontend build:

```powershell
cd frontend
npm.cmd run build
```

Result:

```text
Application bundle generation complete.
```

Known warnings remain:

- `marketplace-home.component.ts` component CSS budget exceeds 4.00 kB by
  `808 bytes`.
- `marketplace-layout.component.ts` component CSS budget exceeds 4.00 kB by
  `1.31 kB`.

Those warnings remain deferred to `PRESEARCH-STAB-P2-01`.

## Acceptance Criteria

- Components are easier to review.
- Existing user behavior is unchanged.
- Existing tests pass.
