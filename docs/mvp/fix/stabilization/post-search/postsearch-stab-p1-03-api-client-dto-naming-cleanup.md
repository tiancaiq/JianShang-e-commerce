# POSTSEARCH-STAB-P1-03 API Client And DTO Naming Cleanup

Status: complete.

## Goal

Clarify API client and frontend DTO names after the marketplace and business
storefront split, without changing wire contracts.

## Changes

- Added surface-specific Angular aliases:
  - `MarketplaceListingSearchPage`
  - `BusinessStoreListingSort`
  - `BusinessStoreListingSearchParams`
  - `BusinessStoreListingSearchPage`
- Updated `ListingService` method signatures so marketplace and business-store
  search calls expose surface-specific types.
- Updated the business storefront component to use business-store search type
  names instead of generic public-search names.
- Renamed the listing-service query-string helper to the neutral
  `listingSearchHttpParams`.

## Behavior

No behavior change is intended.

- Endpoint paths are unchanged.
- JSON request/query parameter names are unchanged.
- JSON response fields are unchanged.
- Backend DTO names are unchanged because they are part of the current public
  listing API contract and renaming them would add review risk without a wire
  benefit.

## Verification

Passed:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false --include src/app/core/services/listing.service.spec.ts --include src/app/features/stores/business-stores.component.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts
```

Result: 45 specs passed. The run emitted expected test-server 404 warnings for
mock listing-media URLs, but no failures.

## Non-Goals

- No Java DTO rename.
- No API path or JSON field rename.
- No database or migration change.
- No UI behavior change.
- No avatar, storage, chat, checkout, order, notification, review, AI, or
  analytics behavior.
