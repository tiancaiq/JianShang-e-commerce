# USER-03 Account Dashboard Shell

Status: complete.

## Goal

Add a compact marketplace account landing area so signed-in users have one
clear home for profile and individual seller account actions.

## Implemented Behavior

- Added protected marketplace route `/account`.
- `/account` renders a small operational dashboard, not a marketing page.
- The dashboard links to implemented account routes:
  - `/account/profile`
  - `/account/seller-profile`
  - `/account/listings`
  - `/account/listings/new`
- The dashboard shows a disabled Messages card as a reserved future area, with
  no active chat route.
- Marketplace authenticated navigation now points `Account` to `/account`.
- Existing profile, seller profile, and listing routes remain unchanged.

## Preserved Boundaries

- Business seller portal remains under `/seller`.
- Admin portal remains under `/admin`.
- Account dashboard does not expose business inventory, cart, checkout,
  payment, orders, shipping, notifications, reviews, likes, saved listings, or
  active chat.

## Files Changed

- `frontend/src/app/features/account/account-dashboard.component.ts`
- `frontend/src/app/features/account/account-dashboard.component.spec.ts`
- `frontend/src/app/app.routes.ts`
- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/iam/user-profile/user-03-account-dashboard-shell.md`

## Verification

Focused frontend tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/account/account-dashboard.component.spec.ts --include=src/app/app.routes.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

## Non-Goals

- No backend changes.
- No database migration.
- No API contract change.
- No chat implementation.
- No likes, saved listings, reviews, notifications, orders, cart, checkout, or
  payments.
- No profile field changes.
