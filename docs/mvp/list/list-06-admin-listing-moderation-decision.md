# LIST-06 Admin Listing Moderation Decision

Status: complete.

## Scope

LIST-06 lets a platform admin review submitted listings and record an
approve, reject, or request-changes decision.

This slice does not implement moderation case claiming, public listing detail,
search, storefront pages, real image byte storage, inventory, checkout,
payment, orders, or shipping.

ADM-LIST-00 through ADM-LIST-03 later add the case-backed admin queue,
claim/release, review detail, and case resolution flow used by the admin MVP
site. The direct listing decision endpoint remains the basic LIST-06 contract.

## Backend

Implemented endpoints:

```text
GET  /api/v1/admin/listings/moderation
POST /api/v1/admin/listings/{listingId}/decision
```

Decision headers:

```text
If-Match: 1
```

Decision request:

```json
{
  "decision": "APPROVE",
  "reason": "Listing is complete and images are acceptable."
}
```

Rules:

- Requires authenticated platform admin role `PLATFORM_ADMIN`.
- Queue reads return listings with `status=PENDING_REVIEW` and
  `moderationStatus=PENDING`.
- Decisions require a nonblank reason.
- `APPROVE` changes listing status to `ACTIVE`, moderation status to
  `APPROVED`, and sets `publishedAt`.
- `REJECT` changes listing status to `REJECTED` and moderation status to
  `REJECTED`.
- `REQUEST_CHANGES` changes listing status to `CHANGES_REQUESTED` and
  moderation status to `CHANGES_REQUESTED`.
- Attached listing images and media metadata receive the same moderation
  result.
- A stale `If-Match` version returns `409 LISTING_VERSION_CONFLICT`.
- Every decision is appended to immutable decision history.

## Frontend

The admin portal now includes:

```text
/admin/listings/moderation
```

The page loads the submitted listing queue, displays listing details and
attached image metadata, and lets the admin submit one of the three supported
decisions with a required reason.

## Persistence

Added migrations:

- `V202606170400__create_listing_moderation_decisions.sql`
- `V202606170500__allow_listing_image_changes_requested.sql`

New table:

- `listing_moderation_decisions`

Stored decision history includes listing ID, decision, reason, reviewer user
ID, listing version, and creation timestamp.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl auth-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Moderation case assignment/claiming.
- Public approved listing detail and browse/search pages.
- Seller-facing reason display beyond current status.
- Real object storage and image preview URLs.
