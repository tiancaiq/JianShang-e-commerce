# POSTSEARCH-STAB-P0-03 Public Search And Storefront Contract Audit

Status: complete.

## Goal

Audit the completed public marketplace search, business storefront search,
shared filters, cursor pagination, OpenSearch projection, and public response
safety after the search/profile work. This stabilization slice does not add a
new product feature.

## Files Reviewed Or Touched

- `product-service/src/main/java/com/msb/ecom/product_service/controller/ListingController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/ListingService.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingDraftRepository.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingMediaRepository.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingImageResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/search/OpenSearchListingSearchClient.java`
- `product-service/src/main/java/com/msb/ecom/product_service/search/ListingSearchDocument.java`
- `frontend/src/app/core/services/listing.service.ts`
- `frontend/src/app/core/services/listing.service.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `frontend/src/app/features/stores/business-stores.component.ts`
- `frontend/src/app/features/stores/business-stores.component.spec.ts`
- `docs/mvp/api-contract.md`

## Audit Results

### Public Search Surface

Result: pass.

- `GET /api/v1/public/marketplace/listings/search` calls the individual
  marketplace search path and returns approved active `INDIVIDUAL` listings.
- `GET /api/v1/public/stores/listings/search` calls the business storefront
  search path and returns approved active `BUSINESS` listings.
- MySQL-backed queries require `status = ACTIVE`,
  `moderation_status = APPROVED`, and the requested seller type.
- Frontend marketplace rendering filters to `sellerType === 'INDIVIDUAL'` as a
  defensive UI guard.
- Frontend storefront rendering filters to `sellerType === 'BUSINESS'` as a
  defensive UI guard.

### Filters And Pagination

Result: pass.

- Both split search endpoints support `q`, `categoryId`, `condition`,
  `minPrice`, `maxPrice`, `city`, `county`, `sort`, `cursor`, and `limit`.
- `region` is still accepted as a backward-compatible alias when `county` is
  absent.
- Supported sort values are `newest`, `price_asc`, and `price_desc`.
- Invalid sort values return `LISTING_INVALID_REQUEST`.
- Cursor pagination is deterministic because every sort includes a stable
  `publishedAt`/`id` tie-breaker.
- Frontend `Load more` appends the next page to the existing signal array on
  both marketplace and storefront pages.

Test gap: backend tests cover `newest` cursor pagination on the individual
marketplace route and `price_asc` cursor pagination on the business storefront
route. There is not yet a dedicated `price_desc` cursor pagination test for
each route.

### OpenSearch Projection

Result: pass with one defense-in-depth follow-up.

- OpenSearch is disabled by default; MySQL remains the default public search
  source.
- When OpenSearch is enabled, the OpenSearch query returns candidate listing
  IDs only, with `_source` disabled.
- Product-service reloads candidate IDs from MySQL before returning the public
  response.
- The MySQL ID reload revalidates `status = ACTIVE` and
  `moderation_status = APPROVED`.
- Public OpenSearch documents contain public listing fields only and do not
  include owner private IDs, email, phone, exact location, moderation internals,
  storage bucket/key, checkout, payment, order, or shipping fields.
- OpenSearch failures raise `LISTING_SEARCH_UNAVAILABLE` and are mapped to HTTP
  `503` when search depends on the derived index.

Follow-up: for extra protection against a corrupted or stale search index,
consider rechecking the requested seller type during the MySQL ID reload after
OpenSearch returns candidate IDs.

### Public Response Safety

Result: pass after copy and documentation cleanup.

- Public listing responses omit owner private user IDs from JSON. The internal
  seller ID is marked `@JsonIgnore`.
- Public listing responses omit business internal membership/staff data,
  internal listing status, moderation status, versions, media object IDs,
  bucket, and object key.
- Public listing media uses app-owned read URLs:
  `/api/v1/public/listing-media/{imageId}`.
- Public listing media DTOs do not expose raw object storage URLs, signed
  upload URLs, buckets, or keys.
- Public listing location exposes city/region only, not exact individual
  addresses.
- Individual listing detail keeps the off-platform payment and delivery notice.
- Business public listing detail now uses neutral view-only MVP wording instead
  of deferred checkout wording.

## Changes Made

- Replaced one public business listing detail phrase that mentioned checkout
  with neutral MVP view-only wording.
- Added a component regression test to keep public business listing detail copy
  away from checkout/payment/order/shipping language.
- Updated the public listing detail API example to show app-owned public media
  URLs instead of a local-demo storage placeholder.
- Documented that public image URLs must not expose raw storage URLs, signed
  upload URLs, buckets, or keys.

## Verification

Focused verification:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/core/services/listing.service.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts --include src/app/features/marketplace/public-listing-detail.component.spec.ts --include src/app/features/stores/business-stores.component.spec.ts
```

Results:

- `.\mvnw.cmd -pl product-service -am test` could not start Maven from the
  local PowerShell wrapper.
- `C:\Users\b\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd -pl product-service -am test`
  passed. Reactor result: common-core, common-web, and product-service
  succeeded; product-service reported 102 tests, 0 failures, 0 errors.
- Frontend focused test command passed after running outside the sandbox so
  Angular/Karma could read `node_modules`. Result: 48 tests, 0 failures.

## Non-Goals

- No cart, inventory, checkout, payment, order, shipping, notification, review,
  AI, or trade-completion behavior was added.
- No new search filters or ranking behavior were added.
- No migration was added.
