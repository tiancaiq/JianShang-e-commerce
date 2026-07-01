# SEARCH-02B Public Business Storefront Browse

Status: planned.

## Goal

Let guests view an approved business storefront and its approved active
business listings as a separate experience from individual marketplace search.

## Scope

- Add a guest-readable storefront endpoint.
- Add a storefront listing browse endpoint scoped to one approved active
  business.
- Show safe store display information and approved active business listing
  cards.
- Reuse safe public listing projection rules where possible.

## Out Of Scope

- Individual marketplace keyword search.
- Business inventory reservation.
- Cart, checkout, payment, orders, shipping, fulfillment, or returns.
- Store staff/member management.
- OpenSearch. Use MySQL first.

## Acceptance Criteria

- Guests can view an active approved business storefront.
- Guests can browse that store's approved active business listings.
- Suspended, inactive, draft, pending-review, rejected, closed, and individual
  listings do not appear in the storefront listing path.
- Public response does not expose business application internals, staff
  membership records, private contact data, internal moderation data, or media
  bucket/key.
- Storefront UI does not show cart, inventory, checkout, payment, order, or
  shipping features.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl auth-service,product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```
