# STAB-P0-03 V2 Demo UI Quarantine

Status: complete.

## Goal

Remove or quarantine V2/tutorial commerce UI from active MVP navigation without
adding features or changing product behavior.

## Decision

Quarantine the old tutorial UI instead of deleting it in this slice.

Reasons:

- The active MVP route tree already uses the three approved surfaces:
  marketplace, business seller portal, and admin portal.
- Deleting the old tutorial files would be a larger cleanup and could mix
  behavior removal with documentation cleanup.
- Keeping them clearly labeled lets V2 work reuse useful examples later while
  preventing teammates from wiring them into MVP navigation by accident.

## Quarantined UI

These files are V2/tutorial only and must not be mounted in active MVP routes:

- `frontend/src/app/layout/shell/shell.component.ts`
- `frontend/src/app/layout/sidebar/sidebar.component.ts`
- `frontend/src/app/layout/navbar/navbar.component.ts`
- `frontend/src/app/features/dashboard/dashboard.component.ts`
- `frontend/src/app/features/products/product-list.component.ts`
- `frontend/src/app/features/products/product-create.component.ts`
- `frontend/src/app/features/inventory/inventory-check.component.ts`
- `frontend/src/app/features/orders/order-list.component.ts`
- `frontend/src/app/features/orders/order-place.component.ts`
- `frontend/src/app/features/payments/payment-list.component.ts`
- `frontend/src/app/features/payments/payment-process.component.ts`
- `frontend/src/app/core/services/product.service.ts`
- `frontend/src/app/core/services/inventory.service.ts`
- `frontend/src/app/core/services/order.service.ts`
- `frontend/src/app/core/services/payment.service.ts`
- `frontend/src/app/core/models/product.model.ts`
- `frontend/src/app/core/models/order.model.ts`
- `frontend/src/app/core/models/payment.model.ts`

## Active MVP Navigation

Allowed MVP surfaces remain:

- Public marketplace: `/`, `/listings/:listingId`, `/sell`,
  `/account/profile`, `/account/seller-profile`, `/account/listings`
- Business seller portal: `/seller/dashboard`, `/seller/business/apply`,
  `/seller/profile`
- Admin portal: `/admin/business-applications`,
  `/admin/listings/moderation`

The active route tree must not expose:

- `/products`
- `/orders`
- `/payments`
- `/inventory`
- `/cart`
- `/checkout`
- `/wallet`
- `/notifications`

## Changes Made

- Added guardrail tests that fail if active routes expose V2 demo commerce
  paths.
- Added layout tests that fail if marketplace, seller, or admin navigation
  exposes V2 commerce labels.
- Added code comments marking the old shell/sidebar/navbar as quarantined
  tutorial UI.

## Non-Goals

- No cart, checkout, payment, inventory, order, shipping, wallet, or
  notification features were added.
- No backend V2 service stubs were removed.
- No tutorial components, services, or models were deleted.
- No active product behavior was changed.

## Verification

Run:

```powershell
npm.cmd test -- --include src/app/app.routes.spec.ts --watch=false
npm.cmd test -- --include src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts --include src/app/layout/seller-layout/seller-layout.component.spec.ts --include src/app/layout/admin-layout/admin-layout.component.spec.ts --watch=false
npm.cmd run build
```
