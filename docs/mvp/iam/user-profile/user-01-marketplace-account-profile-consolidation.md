# USER-01 Marketplace Account Profile Consolidation

Status: complete.

## Goal

Make `/account/profile` the canonical marketplace user profile route while
preserving the existing IAM-06 profile behavior.

This slice consolidates routing and navigation only. It does not add profile
fields, change API contracts, or change persistence.

## Implemented Behavior

- `/account/profile` remains the protected marketplace account profile route.
- Legacy `/profile` continues to redirect to `/account/profile`.
- `/seller/profile` now redirects to `/account/profile` instead of loading the
  basic user profile inside the business seller portal.
- Seller portal navigation labels the basic profile entry as `Account` and
  links to `/account/profile`.
- Seller dashboard account action also links to `/account/profile`.
- Marketplace header account navigation already points to `/account/profile`
  and remains unchanged.

## Preserved Profile Contract

The profile page still uses the existing IAM-06 APIs:

```text
GET   /api/v1/users/me
PATCH /api/v1/users/me
```

Editable fields remain limited to:

- `displayName`
- `phone`
- `avatarUrl`

Email, email verification, roles, status, internal IDs, and Keycloak subject
remain non-editable by the user.

## Files Changed

- `frontend/src/app/app.routes.ts`
- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.spec.ts`
- `frontend/src/app/features/seller/seller-dashboard.component.ts`
- `frontend/src/app/features/seller/seller-dashboard.component.spec.ts`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/iam/user-profile/user-01-marketplace-account-profile-consolidation.md`

## Verification

Frontend focused tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/app.routes.spec.ts --include=src/app/layout/seller-layout/seller-layout.component.spec.ts --include=src/app/features/seller/seller-dashboard.component.spec.ts --include=src/app/features/account/profile.component.spec.ts --include=src/app/core/services/user-profile.service.spec.ts --include=src/app/core/guards/auth.guard.spec.ts
```

## Non-Goals

- No backend changes.
- No database migration.
- No API contract change.
- No avatar upload.
- No email change or reverification flow.
- No phone verification flow.
- No address book.
- No chat, likes, reviews, notifications, or AI behavior.
- No business store profile behavior.
