# LIST-01 Create Listing Draft

## Scope

LIST-01 lets an authenticated seller save a draft listing.

Implemented:

- `POST /api/v1/listings`
- Draft status only: `DRAFT` and `NOT_SUBMITTED`
- Active individual seller authorization
- Active business membership authorization with `LISTING_DRAFT_CREATE`
- Angular draft listing form at `/listings/new`

Not implemented:

- publish or moderation submission
- image upload or attachment
- public listing detail
- search or storefront
- chat or trade creation
- inventory, checkout, or orders

## Ownership Rules

Individual draft:

- requester must have an active individual seller profile
- owner user ID is derived from auth-service
- quantity is exactly `1`
- status and moderation status are server-owned

Business draft:

- requester must have active membership for the requested business
- membership must include `LISTING_DRAFT_CREATE`
- current MVP grants this permission to `OWNER` and `MANAGER`
- business ID is validated by auth-service membership lookup
- listing is non-negotiable

## API

```text
POST /api/v1/listings
```

Individual request:

```json
{
  "sellerType": "INDIVIDUAL",
  "categoryId": "01K...",
  "title": "Used bicycle",
  "description": "A reliable city bike.",
  "condition": "GOOD",
  "price": {"amount": 250.00, "currency": "USD"},
  "negotiable": true,
  "location": {"city": "Irvine", "region": "CA"},
  "quantity": 1
}
```

Business request:

```json
{
  "sellerType": "BUSINESS",
  "businessId": "01B...",
  "categoryId": "01K...",
  "title": "Packaged keyboard",
  "description": "New keyboard from store inventory.",
  "condition": "NEW",
  "price": {"amount": 59.99, "currency": "USD"},
  "sku": "SKU-100",
  "quantity": 3
}
```

Client-supplied owner IDs, status, moderation status, version, and timestamps
are ignored because those fields are not part of the request contract.

## Supporting Reference Data

LIST-01 seeds a small active category set so local draft creation has valid
category IDs. The frontend loads:

```text
GET /api/v1/categories
```

This is only reference data for the draft form; it does not enable public
browse/search.

## Local Verification

Run product-service tests:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl product-service -am test
```

Run frontend checks:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```
