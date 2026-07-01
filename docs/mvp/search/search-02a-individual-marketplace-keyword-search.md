# SEARCH-02A Individual Marketplace Keyword Search

Status: planned.

## Goal

Make the public marketplace search box backend-backed for approved active
individual listings.

## Scope

- Add a guest-readable search endpoint for `INDIVIDUAL` listings.
- Search safe public listing fields such as title, description, category,
  condition, public city, public region, and safe seller display label when
  available.
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
- Draft, pending-review, rejected, closed, suspended, and business listings do
  not appear in this individual search path.
- Public response does not expose owner IDs, private contact data, internal
  status, moderation details, media bucket/key, or exact locations.
- Frontend search input calls the backend search path instead of relying only
  on browser-side filtering.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```
