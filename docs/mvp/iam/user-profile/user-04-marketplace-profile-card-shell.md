# USER-04 Marketplace Profile Card Shell

Status: complete.

## Goal

Add a marketplace-styled user profile card to the main marketplace experience,
inspired by the visual direction for user-centered marketplace profiles,
without adding backend reputation, review, like, notification, or avatar-upload
behavior.

## Implemented Behavior

- Added a reusable frontend profile card component.
- The card displays:
  - circular avatar image when `users.avatarUrl` is already present
  - initials fallback when no avatar URL exists
  - display name
  - generated handle-style label
  - marketplace seller badge
  - placeholder stat row for listings, trades, and rating
  - `Sell an Item` link to `/account/listings/new`
- Integrated the card into `/account`.
- Integrated the signed-in card into the main marketplace home right rail above
  filters.

## Placeholder Rules

The stat row is presentation-only for this slice:

- Listings uses `--`.
- Trades uses `Soon`.
- Rating uses `New`.

These values do not claim verified reputation, completed trades, reviews, or
ratings. Real counts and ratings require later feature slices.

## Deferred Follow-Up

Avatar upload should be implemented separately as a backend/storage slice,
because it needs upload validation, object storage, authorization, and profile
update behavior.

## Files Changed

- `frontend/src/app/features/account/user-profile-card.component.ts`
- `frontend/src/app/features/account/user-profile-card.component.spec.ts`
- `frontend/src/app/features/account/account-dashboard.component.ts`
- `frontend/src/app/features/account/account-dashboard.component.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/iam/user-profile/user-00-user-profile-roadmap.md`
- `docs/mvp/iam/user-profile/user-04-marketplace-profile-card-shell.md`

## Verification

Focused frontend tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/account/user-profile-card.component.spec.ts --include=src/app/features/account/account-dashboard.component.spec.ts
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/marketplace/marketplace-home.component.spec.ts
```

## Non-Goals

- No backend changes.
- No database migration.
- No avatar upload.
- No public profile route.
- No real ratings, reviews, trades, likes, wishlist, cart, notifications, or
  chat implementation.
