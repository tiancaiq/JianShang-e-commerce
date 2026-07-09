# BUS-LIST-03 Store Item Media

Status: complete.

## Goal

Let approved business sellers upload, attach, preview, remove, and reorder
images for business store item drafts.

This slice only adds media management for store item drafts. It does not
publish items to `/stores`, add item-level admin approval, or introduce
payment, cart, checkout, inventory reservation, orders, shipping, fulfillment,
or notifications.

## Implemented

- `POST /api/v1/businesses/{businessId}/store/items/{listingId}/media/upload-request`
- `PUT /api/v1/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/content`
- `POST /api/v1/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/confirm`
- `PUT /api/v1/businesses/{businessId}/store/items/{listingId}/images`
- business-scoped frontend media upload flow for `/seller/store/items/new`
  and `/seller/store/items/:listingId/edit`
- image remove and ordered image-set updates from the store item form
- existing JPEG, PNG, WebP, size, duplicate-media, uploaded-media, and max
  image-count validation reused from listing media

## Rules

- The requester must have an active approved business and active store context.
- The path business ID must match the current business/store context.
- The item must be `sellerType=BUSINESS` and belong to the active store ID.
- Store item media remains private seller-preview media until BUS-LIST-04
  publishes the item.
- Client-provided owner, business, store, status, moderation, payment,
  inventory, order, shipping, or transaction data is not trusted.

## Deferred

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
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/core/services/listing.service.spec.ts --include src/app/features/listings/listing-media-upload.service.spec.ts --include src/app/features/listings/listing-draft-form.component.spec.ts
npm.cmd run build
```
