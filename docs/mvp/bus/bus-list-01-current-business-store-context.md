# BUS-LIST-01 Current Business/Store Context

Status: complete.

## Goal

Give the business seller portal a current-user context endpoint before store
item management begins.

The endpoint answers: does this authenticated user currently have an active,
approved business and active store they can manage?

## Scope

Implemented:

- `GET /api/v1/businesses/me/store-context`
- returns `data: null` when the user has no active approved business/store
  context
- returns business ID/name/status, current membership role, permissions, and
  active store profile when context exists
- Angular `BusinessStoreService.getCurrentStoreContext()`
- seller business account page uses the context to link directly to the
  approved business store profile

Not implemented:

- store item create/edit/publish
- public store item visibility changes
- inventory, cart, checkout, payment, orders, shipping, or fulfillment
- staff invitation or multi-business selection UX

## API

```text
GET /api/v1/businesses/me/store-context
```

Response without approved business/store context:

```json
{"data": null}
```

Response with context:

```json
{
  "data": {
    "businessId": "01JY...",
    "businessLegalName": "Acme Trading LLC",
    "businessStatus": "ACTIVE",
    "membershipRole": "OWNER",
    "permissions": ["LISTING_DRAFT_CREATE"],
    "store": {
      "id": "01JY...",
      "businessId": "01JY...",
      "slug": "acme-trading",
      "name": "Acme Trading",
      "description": "Local goods",
      "logoUrl": null,
      "bannerUrl": null,
      "supportEmail": "help@example.com",
      "supportPhone": "+19495550000",
      "status": "ACTIVE",
      "version": 1,
      "createdAt": "2026-07-08T12:00:00Z",
      "updatedAt": "2026-07-08T12:05:00Z"
    }
  }
}
```

## Rules

- Requires authentication.
- Only active business memberships are considered.
- Only active businesses and active stores are returned.
- No business application internals, staff membership records, private owner
  IDs, payment, inventory, order, or transaction fields are returned.
- With the current one-business-account rule, the endpoint returns at most one
  context. If future staff membership allows multiple businesses, add explicit
  business switching instead of overloading this endpoint.

## Verification

Expected local checks:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl auth-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```
