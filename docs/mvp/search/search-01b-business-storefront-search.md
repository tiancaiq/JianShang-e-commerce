# SEARCH-01B Business Storefront Search

Status: complete.

Planned update: `BUS-LIST-00` changes business store item visibility so
approved businesses can publish complete store items to `/stores` without
item-level admin approval. This slice describes the current implemented
approved-listing search behavior; `BUS-LIST-00` supersedes that approval rule
for business store items.

## Goal

Let guests search active business listings as a separate experience from
individual marketplace search.

## Scope

- Use the guest-readable `GET /api/v1/public/stores/listings/search` endpoint.
- Current implementation returns only approved active `BUSINESS` listings.
  The planned store item flow returns active business self-published store
  items without item-level admin approval.
- Filter by keyword, category, condition, min/max price, city, and county.
- Sort by newest, price low to high, or price high to low when the user
  explicitly selects sorting. The default UI state sends no `sort` parameter.
- Show safe store display grouping and approved active business listing cards.
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
- Public response does not expose business application internals, staff
  membership records, private contact data, internal moderation data, or media
  bucket/key.
- Storefront UI does not show cart, inventory, checkout, payment, order, or
  shipping features.
- Default sort sends no `sort` query parameter.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests#businessStoreListingsSearchReturnsOnlyApprovedBusinessListings+businessStoreListingsSearchAppliesKeywordCategoryConditionPriceAndLocationFilters+businessStoreListingsSearchSortsByPrice+businessStoreListingsSearchRejectsInvalidSort" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl api-gateway -am test "-Dtest=ApiGatewayApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --include=src/app/core/services/listing.service.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts
```
