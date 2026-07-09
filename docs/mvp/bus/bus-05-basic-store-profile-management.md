# BUS-05 Basic Store Profile Management

Status: implemented.

## Scope

BUS-05 lets approved business owners and managers maintain the customer-facing
MVP store profile. Approval creates one default active store for the business;
existing approved businesses are backfilled by the BUS-05 migration.

Implemented behavior:

- `GET /api/v1/businesses/{businessId}/store`
- `PATCH /api/v1/businesses/{businessId}/store`
- `GET /api/v1/stores/{slug}`
- creates a `stores` row with one store per approved business
- backfills default stores for already approved businesses
- creates a default store during future business approvals
- enforces active business membership for protected store reads
- allows only `OWNER` and `MANAGER` memberships to edit
- requires `If-Match` for store edits
- enforces unique public store slugs
- adds Angular seller route `/seller/businesses/:businessId/store`
- links approved business applications to the store profile screen

## Non-goals

- no staff invitations or permission management
- no store policies
- no business inventory
- no cart, checkout, payment, orders, shipping, or fulfillment
- no listing publish workflow changes
- no logo upload pipeline; BUS-05 stores a URL/API path only

## Verification

Backend:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl auth-service -am test
```

Frontend:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

Manual test:

1. Submit and approve a business application.
2. Open `/seller/businesses/{businessId}/store` as the owner.
3. Edit name, slug, description, and support contact fields.
4. Confirm stale `If-Match` values return `409 VERSION_CONFLICT`.
5. Open `/api/v1/stores/{slug}` without login and confirm only the active
   public store profile is returned.
