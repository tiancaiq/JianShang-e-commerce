# LIST-05 Submit Listing for Moderation

Status: complete.

## Scope

LIST-05 lets a seller submit an owned draft listing for review.

This slice does not implement admin moderation decisions, moderation queues,
public listing detail, search, real image byte storage, inventory, checkout,
payment, orders, or shipping.

## Backend

Implemented endpoint:

```text
POST /api/v1/listings/{listingId}/submit
```

Headers:

```text
If-Match: 0
```

Rules:

- Listing must exist and be owned by the current actor.
- Listing must currently be `DRAFT`.
- At least one attached uploaded image is required.
- A stale `If-Match` version returns `409 LISTING_VERSION_CONFLICT`.
- On success, listing `status` becomes `PENDING_REVIEW`.
- On success, listing `moderationStatus` becomes `PENDING`.
- Attached listing images and their media metadata move to moderation
  `PENDING`.
- Normal draft edits are blocked while the listing is in `PENDING_REVIEW`.

## Frontend

The seller draft edit page now shows a `Submit for review` action when:

- the listing is opened in edit mode,
- the listing is still `DRAFT`, and
- at least one image is attached.

After successful submission, the form becomes read-only and displays the
submitted status.

## Persistence

No migration was added. LIST-05 uses existing listing lifecycle fields:

- `listings.status`
- `listings.moderation_status`
- `listing_images.moderation_status`
- `listing_media_objects.moderation_status`
- optimistic `version` columns

Moderation case tables and reviewer decision history remain deferred to the
listing moderation slice.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Admin listing moderation decision.
- Moderation queue/case table.
- Seller-facing decision reason for rejection or changes requested.
- Approved public listing detail and search/browse.
- Real image object storage and preview URLs.
