# SEARCH-05 Search State And Visibility Hardening

Status: complete.

## Goal

Stabilize the completed public search paths without merging individual
marketplace and business store search.

## Scope

- Keep the public search endpoints split:
  - `GET /api/v1/public/marketplace/listings/search`
  - `GET /api/v1/public/stores/listings/search`
- Make frontend search state URL-based for query, category, condition, price,
  city, county, and sorting.
- Keep default sorting as no explicit `sort` query parameter.
- Add business store-name and SKU keyword matching to the business search path.
- Revalidate business search results against auth-service public store
  visibility.
- Degrade gracefully when auth-service seller-label enrichment is unavailable:
  individual marketplace listings still load with neutral seller labels, while
  business store visibility checks fail closed.
- Improve empty states with the active filter summary, clear-filters action,
  suggested category text, and browse-all actions.

## Visibility Rules

Individual marketplace search returns only active approved individual listings.

Business store search returns only active business listings that also belong to
an active business and active store according to auth-service. Draft, paused,
removed, inactive, suspended, closed, and cross-business items must not leak.

OpenSearch, when enabled, remains a candidate-ID projection only. Product
service reloads and revalidates results before returning public cards.

## Business Keyword Matching

Business search keyword matching covers:

- item title
- item description
- SKU
- category name/slug
- condition
- public city/county
- public active store name
- public active business legal name

Store-name and business-name matching is resolved through auth-service so
product-service does not query another service's database.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests#businessStoreListingsSearchMatchesSkuAndStoreName+businessStoreListingsSearchHidesItemsWhenBusinessStoreIsNotPublicActive+businessStoreListingsSearchAppliesKeywordCategoryConditionPriceAndLocationFilters,OpenSearchListingSearchClientTests" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl auth-service -am test "-Dtest=AuthServiceApplicationTests#guestCanSearchPublicActiveBusinessStoresByStoreOrLegalName+publicBusinessStoreSearchHidesSuspendedStoresAndBusinesses" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/core/services/listing.service.spec.ts
```

`V202607181800__create_user_addresses.sql` uses a generated `default_marker`
and a unique `(user_id, default_marker)` constraint so clean MySQL databases
can migrate while still allowing only one default address per user.
