# SEARCH-01A Individual Marketplace Search

Status: complete.

Note: earlier roadmap drafts called this slice `SEARCH-02A`. The implemented
scope is the current `SEARCH-01A` individual marketplace search slice.

## Goal

Make the public marketplace search box and filters backend-backed for approved
active individual listings.

## Scope

- Use the guest-readable `GET /api/v1/public/marketplace/listings/search`
  endpoint for `INDIVIDUAL` listings.
- Search safe public listing fields such as title, description, category,
  condition, public city, and public county/region.
- Filter by keyword, category, condition, min/max price, city, and county.
- Sort by newest, price low to high, or price high to low when the user
  explicitly selects sorting. The default UI state sends no `sort` parameter.
- Return only `status=ACTIVE` and `moderation_status=APPROVED` listings.
- Keep the existing public listing card projection.
- Keep individual trade disclosure visible.

## Out Of Scope

- Business storefront browse.
- Cart, checkout, inventory, payment, orders, shipping, or buy-now actions.
- Structured offer/counteroffer workflows.
- Chat/contact seller, which belongs to CHAT slices.
- OpenSearch. Use MySQL first.

## Acceptance Criteria

- Guests can keyword-search approved active individual listings.
- Guests can filter by category, condition, price range, city, and county.
- Guests can sort by newest, price low to high, and price high to low.
- Draft, pending-review, rejected, closed, suspended, and business listings do
  not appear in this individual search path.
- Public response does not expose owner IDs, private contact data, internal
  status, moderation details, media bucket/key, or exact locations.
- Frontend marketplace search and filters call the backend search path instead
  of relying on browser-side filtering.
- Frontend query, category, condition, price, city, county, and sort state are
  represented in URL query parameters so refresh, back navigation, and shared
  links preserve the active search.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```
