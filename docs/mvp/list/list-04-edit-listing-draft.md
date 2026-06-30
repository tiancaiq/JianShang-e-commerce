# LIST-04 Edit Listing Draft

Status: complete.

## Scope

LIST-04 lets a seller reopen and edit an existing draft listing before listing
submission and moderation.

This slice does not submit listings for moderation, publish listings, expose
public listing pages, implement search, or add inventory/order/payment flows.

## Backend

Implemented endpoints:

```text
GET   /api/v1/listings/{listingId}
GET   /api/v1/users/me/listings
GET   /api/v1/businesses/{businessId}/listings
PATCH /api/v1/listings/{listingId}
```

`PATCH /api/v1/listings/{listingId}` requires the current draft version in the
`If-Match` header. A stale version returns `409 LISTING_VERSION_CONFLICT`.

Rules:

- Listing must exist and be owned by the current actor.
- Listing must remain in `DRAFT`.
- Only editable draft content fields can change.
- Client-supplied owner, status, and moderation fields are ignored.
- Individual listing quantity remains exactly `1`.
- Business listing updates require the same `businessId` and business
  membership authorization.
- Category IDs must reference an active category.

## Frontend

Implemented marketplace account routes:

```text
/account/listings
/account/listings/new
/account/listings/:listingId/edit
```

Legacy `/seller/listings...` paths redirect to these marketplace account
routes for compatibility only.

The seller listing page shows current seller drafts and links each draft to the
edit form. Creating a draft now redirects to the edit route so the saved draft
has a stable place in the UI. The edit form loads the saved draft, sends
updates with `If-Match`, keeps seller type and business ownership locked, and
continues to support the existing draft image flow.

## Persistence

No migration was added. LIST-04 uses the existing `listings` version column and
draft fields created by LIST-00/LIST-01.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Submit listing for moderation.
- Admin listing moderation decision.
- Public listing detail and browse/search.
- Business inventory, checkout, payment, orders, and shipping.
