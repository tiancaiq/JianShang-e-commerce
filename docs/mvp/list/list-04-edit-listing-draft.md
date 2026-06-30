# LIST-04 Edit Listing Draft

Status: complete.

## Scope

LIST-04 lets an individual seller edit marketplace listings before or after
moderation submission. Edits to pending-review, active, or closed listings move
the listing back to draft so changed content is no longer publicly visible
until it is resubmitted and approved again.

This slice does not implement search, chat, inventory, order, payment, or
business seller listing workflows.

## Backend

Implemented endpoints:

```text
GET   /api/v1/listings/{listingId}
GET   /api/v1/users/me/listings
GET   /api/v1/businesses/{businessId}/listings
PATCH /api/v1/listings/{listingId}
POST  /api/v1/listings/{listingId}/close
```

`PATCH /api/v1/listings/{listingId}` requires the current draft version in the
`If-Match` header. A stale version returns `409 LISTING_VERSION_CONFLICT`.

Rules:

- Listing must exist and be owned by the current actor.
- Listing can be edited from `DRAFT`, `PENDING_REVIEW`, `ACTIVE`, or `CLOSED`.
- Editing a `PENDING_REVIEW`, `ACTIVE`, or `CLOSED` listing resets it to
  `DRAFT` with `NOT_SUBMITTED` moderation status and removes public visibility.
- Seller can close `DRAFT`, `PENDING_REVIEW`, or `ACTIVE` listings. Closed
  active listings are removed from public browse/detail.
- Only editable draft content fields can change.
- Client-supplied owner, status, and moderation fields are ignored.
- Individual listing quantity is seller-entered and must be at least `1`.
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

The marketplace account listing page shows seller-managed listings and links
each listing to the edit form. Creating a draft redirects to the edit route so
the saved draft has a stable place in the UI. The edit form loads the saved
listing, sends updates with `If-Match`, treats marketplace listings as
individual listings, supports seller-entered quantity, highlights invalid
fields, allows up to 10 images, previews selected images immediately, and lets
the seller remove attached images.

## Persistence

Added `V202606290001__relax_individual_listing_quantity.sql` to allow
individual listing quantity greater than 1 while keeping the minimum at 1.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Search and richer browse filtering.
- Business seller listing management.
- Business inventory, checkout, payment, orders, and shipping.
