# SEARCH-01B Business Item Search

Status: complete.

`BUS-LIST-04` changes business store item visibility so approved businesses can
publish complete store items to `/stores` without item-level admin approval.

## Goal

Let guests search active business item listings as a separate experience from
individual marketplace trade search.

## Scope

- Use the guest-readable `GET /api/v1/public/stores/listings/search` endpoint.
- Current implementation returns active `BUSINESS` listings with
  `publication_source=BUSINESS_SELF_PUBLISHED`.
- Filter by keyword, category, condition, min/max price, city, and county.
- Keyword search includes item title, SKU, store name, business legal name,
  and category fields while keeping product-service and auth-service database
  ownership separate.
- Revalidate results against auth-service so only active businesses with active
  stores are visible.
- Sort by newest, price low to high, or price high to low when the user
  explicitly selects sorting. The default UI state sends no `sort` parameter.
- Show active self-published business item cards with safe business seller
  display metadata.
- Reuse safe public listing projection rules.

## Out Of Scope

- Individual marketplace search.
- One-store slug/detail pages.
- Business inventory reservation.
- Cart, checkout, payment, orders, shipping, fulfillment, or returns.
- Store staff/member management.
- OpenSearch. Use MySQL first.

## Acceptance Criteria

- Guests can search active business store items.
- Suspended, inactive, draft, pending-review, rejected, closed, and individual
  listings do not appear in the store listing path.
- Business listings from inactive or suspended businesses/stores do not appear
  even if the product listing row itself is active.
- Public response does not expose business application internals, staff
  membership records, private contact data, internal moderation data, or media
  bucket/key.
- Business item UI does not show cart, inventory, checkout, payment, order, or
  shipping features.
- Default sort sends no `sort` query parameter.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests#businessStoreListingsSearchReturnsOnlySelfPublishedBusinessListings+businessStoreListingsSearchAppliesKeywordCategoryConditionPriceAndLocationFilters+businessStoreListingsSearchSortsByPrice+businessStoreListingsSearchRejectsInvalidSort" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl api-gateway -am test "-Dtest=ApiGatewayApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --include=src/app/core/services/listing.service.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts
```
