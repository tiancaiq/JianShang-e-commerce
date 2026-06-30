# SEARCH-01 Public Approved Listing Browse

Status: complete.

## Scope

SEARCH-01 replaces the marketplace home placeholder with a simple public list
of approved active listings.

This slice does not implement full-text search, filters, sorting controls,
cursor pagination, OpenSearch, storefront pages, chat, contact seller, cart,
checkout, payment, inventory, orders, or shipping.

## Backend

Implemented endpoint:

```text
GET /api/v1/public/listings
```

Rules:

- Endpoint is public and does not require login.
- Only listings with `status=ACTIVE` and `moderationStatus=APPROVED` are
  returned.
- Response uses the same safe public projection as LIST-07 detail.
- Results are capped server-side at 24 listings and ordered by newest
  published listings.
- Non-public listings are omitted.

Performance contract:

- SEARCH-01 is a fixed-size homepage browse endpoint, not full search.
- The server ignores client-controlled limits because this endpoint currently
  accepts no query parameters.
- The database query must always include a bounded `LIMIT`.
- `idx_listings_public_browse` supports the current
  `status + moderation_status + published_at` access pattern.
- Keyword, filters, custom sort, and cursor pagination remain SEARCH-03.

## Frontend

The marketplace home page at `/` now loads approved public listings and shows
cards linking to:

```text
/listings/{listingId}
```

Cards show title, price, seller type, category, public location, and current
local-demo image metadata.

## Persistence

SEARCH-01 reads existing approved listing rows and approved image/media
metadata. STAB-P1-05 added the public browse index:

- `product-service/src/main/resources/db/migration/catalog/V202606170700__add_public_listing_browse_index.sql`

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

- Search text box and filters.
- Cursor pagination.
- OpenSearch projection.
- Storefront browse.
- Real browser-renderable object storage URLs.
