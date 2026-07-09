# BUS-LIST-02 Store Item Draft and Edit

Status: complete.

## Goal

Let an approved business owner create, view, list, and edit store item drafts
from the business seller portal.

This slice prepares the business catalog authoring path only. It does not make
store items public, publish items to `/stores`, upload item media, or add
payment, cart, checkout, inventory reservation, orders, shipping, fulfillment,
or notifications.

## Implemented

- `GET /api/v1/businesses/{businessId}/store/items`
- `POST /api/v1/businesses/{businessId}/store/items`
- `GET /api/v1/businesses/{businessId}/store/items/{listingId}`
- `PATCH /api/v1/businesses/{businessId}/store/items/{listingId}`
- server-derived `sellerType=BUSINESS`, path `businessId`, and active
  current store context
- `store_id` persisted on new business store item drafts
- optimistic locking for edits through `If-Match`
- seller portal routes:
  - `/seller/store/items`
  - `/seller/store/items/new`
  - `/seller/store/items/:listingId/edit`
- business account page links into the store item management area

## Rules

- The requester must have an active approved business and active store context.
- The path business ID must match the current business/store context.
- Store item drafts are owned by the active store returned by
  `GET /api/v1/businesses/me/store-context`.
- Client-provided seller type, business ID, negotiability, location, status,
  moderation status, payment, inventory, order, or transaction data is not
  trusted.
- Business store item drafts are not public until a later publishing slice.

## Deferred

- BUS-LIST-03: media upload and image ordering for store item drafts.
- BUS-LIST-04: publish, pause, relist, and public `/stores` visibility.
- BUS-LIST-05: reactive admin removal of active business store items.
- V2: payment transaction, cart, checkout, inventory reservation, order,
  shipping, fulfillment, and notification workflows.

## Verification

Expected local checks:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl product-service -am test "-Dtest=ListingDraftApiTests" "-Dsurefire.failIfNoSpecifiedTests=false"
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl api-gateway -am test "-Dtest=ApiGatewayApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/core/services/listing.service.spec.ts --include src/app/features/business/business-account.component.spec.ts --include src/app/features/business/business-store-items.component.spec.ts --include src/app/features/listings/listing-draft-form.component.spec.ts --include src/app/app.routes.spec.ts
npm.cmd run build
```
