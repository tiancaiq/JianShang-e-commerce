# SEARCH-00 Search and Storefront Read Model Plan

Status: complete.

## Scope

SEARCH-00 defines the MVP public browse and storefront read model before adding
more search behavior.

This slice does not implement new runtime behavior, migrations, OpenSearch,
chat, contact-seller actions, cart, checkout, payment, inventory, orders, or
shipping.

## Decisions

- Start with a MySQL-backed public read path owned by `product-service`.
- Use the authoritative `listings`, `categories`, `listing_images`, and
  `listing_media_objects` tables as the source of truth.
- Return only listings with `status=ACTIVE` and `moderation_status=APPROVED`.
- Reuse the same safe public projection for listing cards and listing detail.
- Keep the marketplace homepage listing-first. Secondary actions belong in the
  top navigation or seller/admin route groups, not in the main browse surface.
- Add OpenSearch only after the database-backed browse, storefront, filters,
  sorting, and cursor pagination contracts are stable.

## Public Listing Card Projection

Public listing cards may expose:

- listing ID
- seller type: `INDIVIDUAL` or `BUSINESS`
- category ID, slug, and display name
- title
- condition
- price amount and currency
- negotiable flag
- public city and public region
- published time
- first approved image URL, when available
- individual off-platform transaction notice when relevant

Public listing cards must not expose:

- owner user ID
- business internal staff/member data
- draft/review/rejected/suspended states
- moderation status or decision history
- listing version
- media object bucket/key
- exact individual address or meeting location
- buyer or seller private contact data

## Public Storefront Projection

The MVP storefront read model should expose one public storefront per active
approved business.

Storefront responses may expose:

- business/store public ID or slug
- store display name
- public city and public region
- public description
- website URL, when approved for public display
- public logo/banner URL when media support exists
- approved active business listing cards for that store

Storefront responses must not expose:

- legal verification packet details
- business application internals
- staff membership records
- private contact data
- business admin notes
- inactive, draft, pending, rejected, or suspended listings

## API Shape

Current browse endpoint:

```text
GET /api/v1/public/listings
```

Planned storefront endpoint:

```text
GET /api/v1/public/stores/{storeSlugOrId}
```

Planned browse improvements:

```text
GET /api/v1/public/listings?keyword=&categoryId=&sellerType=&condition=&minPrice=&maxPrice=&city=&region=&sort=&cursor=&limit=
```

Rules:

- Guest access is allowed.
- Cursor pagination is required for unbounded collections.
- Server controls maximum `limit`.
- Sorts must be stable and deterministic.
- Listing detail remains the final revalidation point for public visibility.

## Initial Query Strategy

The MySQL-backed browse path should query approved active listings ordered by
`published_at DESC, id DESC`.

Recommended indexes already align with this direction:

- `(status, seller_type, published_at)`
- `(category_id, status, published_at)`
- `(business_id, status, updated_at)`

SEARCH-03 can add or adjust indexes only when filters/sorts require them.

## OpenSearch Deferral

OpenSearch remains a derived projection, not the source of truth.

Do not introduce OpenSearch until:

- public browse works from MySQL
- business storefronts work from MySQL
- filter and sort contracts are stable
- active listing changes have clear events or rebuild logic
- detail endpoints continue to revalidate public visibility from source data

When added, OpenSearch indexes only safe public fields and must support rebuild
from MySQL plus durable listing events.

## Frontend Direction

The public marketplace route `/` should prioritize listings as the main content.

Recommended layout:

- top navigation: browse, sell, account/login
- centered listing grid as primary page content
- compact count/status near the listing grid
- detail navigation through `/listings/{listingId}`
- individual seller tools stay under marketplace account routes
- business seller tools stay under `/seller`
- admin tools stay under `/admin`

## Verification

No runtime tests are required for SEARCH-00 because it is a planning slice.

Before implementing SEARCH-02 or SEARCH-03, keep the existing verification
baseline green:

```powershell
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Public business storefront implementation: SEARCH-02.
- Keyword/filter/sort/cursor browse: SEARCH-03.
- OpenSearch projection: SEARCH-04.
- Contact seller and chat: CHAT slices.
- Cart, checkout, inventory, payment, orders, and shipping: V2.
