# STAB-P1-05 Public Browse Performance Contract

Status: complete.

## Goal

Tighten the MVP public approved listing browse contract so it cannot
accidentally become an unbounded database read before SEARCH-03 implements
real filters and cursor pagination.

## Scope

This cleanup preserves the existing MVP browse behavior:

- Public guests can fetch newest approved active listings.
- The endpoint accepts no filters, sort, cursor, or client-controlled limit.
- Search text, filters, cursor pagination, OpenSearch, storefront browse, and
  cart/checkout behavior remain deferred.

## Contract

`GET /api/v1/public/listings` is a fixed-size homepage browse endpoint.

Rules:

- Return only `status = ACTIVE` and `moderation_status = APPROVED`.
- Return at most 24 listings.
- Order by `published_at DESC, updated_at DESC, id DESC`.
- Use the safe public listing projection only.
- Include public image metadata through the existing listing image read path.

## Changes

- Added `ListingService.PUBLIC_BROWSE_LIMIT = 24`.
- Updated the repository order to prefer `published_at` directly while still
  returning a legacy-safe `publishedAt` projection with `coalesce`.
- Added `idx_listings_public_browse` for the current public browse access
  pattern.
- Added an API test proving the endpoint caps results at 24.
- Updated SEARCH-01 documentation to describe the fixed-limit contract.

## Migration

- `product-service/src/main/resources/db/migration/catalog/V202606170700__add_public_listing_browse_index.sql`

## Non-Goals

- No full-text search.
- No filter or sort query parameters.
- No cursor pagination.
- No OpenSearch projection.
- No storefront page.
- No frontend UI redesign.

## Verification

Run:

```powershell
.\mvnw.cmd -pl product-service -am test
```
