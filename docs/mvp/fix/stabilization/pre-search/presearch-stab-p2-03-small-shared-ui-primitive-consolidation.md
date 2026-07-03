# PRESEARCH-STAB-P2-03 Small Shared UI Primitive Consolidation

Status: complete.

## Goal

Reduce repeated UI markup only where duplication is already obvious.

This cleanup must keep marketplace, seller portal, and admin portal styles
visually distinct.

## Scope

Candidate repeated structures:

- status pills
- empty states
- simple cards
- table shells
- common action-button wrappers

## Tasks

- Reuse existing shared UI primitives where they already fit.
- Add a new primitive only when it replaces immediate duplication.
- Keep primitives theme-token driven.
- Keep surface-specific product layout inside the owning feature.

## Non-Goals

- No broad design system.
- No UI redesign.
- No new product state.
- No behavior changes.

## Verification

```powershell
cd frontend
npm.cmd test -- --watch=false
```

## Acceptance Criteria

- Duplication decreases.
- Surface-specific styling remains distinct.
- Existing tests pass.

## Completion Notes

- Reused `StatusPillComponent` in:
  - `business-application.component.ts`
  - `admin-business-application-detail.component.ts`
  - `admin-business-application-queue.component.ts`
- Added a `--ui-pill-radius` token to the shared status pill so existing
  business/admin shape could be preserved.
- Removed duplicated local `.status-pill` CSS from those pages.
- No product behavior, route, API, or schema changes were made.

## Verification Results

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/shared/components/ui/status-pill.component.spec.ts --include=src/app/features/business/business-application.component.spec.ts --include=src/app/features/business/admin-business-application-detail.component.spec.ts --include=src/app/features/business/admin-business-application-queue.component.spec.ts
```

Result: `TOTAL: 22 SUCCESS`.
