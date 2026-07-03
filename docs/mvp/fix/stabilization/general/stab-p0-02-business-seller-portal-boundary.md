# STAB-P0-02 Business Seller Portal Boundary

Status: complete.

## Goal

Remove individual seller tools from the business seller portal and make the
marketplace account routes the primary place for personal individual selling.

This slice did not add product features, refactor unrelated code, or change
backend behavior.

## Changes

Frontend routes:

- Kept `/seller` protected as the business seller portal route group.
- Converted legacy `/seller/listings`, `/seller/listings/new`,
  `/seller/listings/:listingId/edit`, and `/seller/activate` child routes into
  redirects to marketplace account routes.
- Added protected marketplace account routes:
  - `/sell`
  - `/account/profile`
  - `/account/seller-profile`
- Kept existing marketplace listing management routes:
  - `/account/listings`
  - `/account/listings/new`
  - `/account/listings/:listingId/edit`

Business seller portal UI:

- Removed `Listings`, `New Listing`, and `Individual Seller` links from the
  business seller sidebar.
- Removed personal listing and individual seller actions from the seller
  dashboard.
- Renamed the dashboard title to `Business Seller Dashboard`.

Marketplace UI:

- `Sell` now enters through `/sell`.
- `Account` now uses `/account/profile`.
- Homepage `List an Item` now uses `/sell`.
- Personal listing components now always link within `/account/listings`.

## Files Changed

- `frontend/src/app/app.routes.ts`
- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- `frontend/src/app/features/seller/seller-dashboard.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/listings/listing-management.component.ts`
- `frontend/src/app/features/listings/listing-management.component.spec.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.spec.ts`

## Compatibility

Legacy URLs are retained as redirects:

```text
/seller/listings              -> /account/listings
/seller/listings/new          -> /account/listings/new
/seller/listings/:id/edit     -> /account/listings/:id/edit
/seller/activate              -> /account/seller-profile
```

Older console aliases now redirect personal seller paths into the marketplace
account area.

## Deferred

- Full business listing management remains a later business/store slice.
- V2 demo UI cleanup remains `STAB-P0-03`.
- Legacy `SidebarComponent` cleanup remains part of `STAB-P0-03` because it
  belongs to the older tutorial shell with V2 navigation.

## Verification

Focused frontend tests:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/app.routes.spec.ts --include src/app/layout/seller-layout/seller-layout.component.spec.ts --include src/app/features/listings/listing-management.component.spec.ts --include src/app/features/listings/listing-draft-form.component.spec.ts --include src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts
```

Result:

```text
TOTAL: 24 SUCCESS
```

Full frontend tests:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Result:

```text
TOTAL: 85 SUCCESS
```

Frontend build:

```powershell
npm.cmd run build
```

Result:

```text
Application bundle generation complete.
```

Backend tests were not run because this slice changed only Angular routes,
navigation, component links, and documentation.

## Acceptance Notes

- Business seller portal no longer displays personal individual seller tools.
- Marketplace account routes remain protected and usable.
- Legacy personal seller URLs redirect to marketplace account routes.
- No cart, checkout, payment, inventory, order, shipping, wallet, or AI
  behavior was added.
