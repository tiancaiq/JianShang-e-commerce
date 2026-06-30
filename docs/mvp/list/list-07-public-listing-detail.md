# LIST-07 Public Listing Detail

Status: complete.

## Scope

LIST-07 lets guests and signed-in users view an approved public listing detail
page by direct listing ID.

This slice does not implement listing browse/search, public storefronts, chat,
contact seller, real image object delivery, OpenSearch, cart, checkout,
payment, inventory, orders, or shipping.

## Backend

Implemented endpoint:

```text
GET /api/v1/public/listings/{listingId}
```

Rules:

- Endpoint is public and does not require login.
- Only listings with `status=ACTIVE` and `moderationStatus=APPROVED` are
  returned.
- Draft, pending-review, rejected, and changes-requested listings return
  `404 LISTING_NOT_FOUND`.
- The public response omits owner user IDs, business internal IDs, status,
  moderation state, versions, media object IDs, object bucket, and object key.
- Public images include only safe display metadata and the current local demo
  display URL.
- Individual listings include the off-platform payment and delivery notice.

## Frontend

Implemented route:

```text
/listings/:listingId
```

The marketplace detail page is guest-accessible and shows title, price,
seller type, category, condition, public location, description, approved image
metadata, and the individual-listing transaction notice when applicable.

## Persistence

No migration was added. LIST-07 reads existing listing, category, and approved
listing image/media rows.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Browse/search page for discovering public listings.
- Public business storefront page.
- Real object storage and browser-renderable image URLs.
- Chat/contact seller.
- Structured offer, cart, checkout, payment, inventory, orders, and shipping.
