# BUS-LIST-04 Self-Publish To Stores

Status: complete.

## Goal

Let approved business store owners publish complete store items to the public
`/stores` surface without item-level admin approval.

## Implemented Behavior

- Seller commands:
  - `POST /api/v1/businesses/{businessId}/store/items/{listingId}/publish`
  - `POST /api/v1/businesses/{businessId}/store/items/{listingId}/pause`
  - `POST /api/v1/businesses/{businessId}/store/items/{listingId}/relist`
- Each state command requires `If-Match`.
- Publish requires the active business store context, a business-owned item,
  an active category, required item fields, positive catalog quantity, and at
  least one attached uploaded image.
- Published items use `status=ACTIVE` and
  `publication_source=BUSINESS_SELF_PUBLISHED`.
- Pause moves a self-published active item to `PAUSED` and removes it from
  public search.
- Relist moves a paused self-published item back to `ACTIVE` while preserving
  the original published timestamp when present.
- Public `/stores` search returns active business self-published items without
  requiring item-level admin approval.

## Boundaries

- Business store items still do not create trades, carts, checkout sessions,
  payments, orders, inventory reservations, shipping, or fulfillment.
- Individual marketplace listings still require admin approval for public
  visibility.
- Business self-published media is public only when attached and uploaded to
  an active self-published business item.

## Verification

- Product-service API coverage verifies publish, pause, relist, public store
  search visibility, and publish validation.
- Frontend coverage verifies business-scoped publish/pause/relist API calls
  and seller portal controls.
