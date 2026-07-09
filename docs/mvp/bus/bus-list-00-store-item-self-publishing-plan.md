# BUS-LIST-00 Store Item Self-Publishing Plan

Status: planned.

## Goal

Let an approved business account upload items to its store and have complete
published items appear on `/stores` without item-level admin approval.

This is a business storefront visibility flow only. Payment transaction,
cart, checkout, inventory reservation, orders, shipping, fulfillment, and
notifications remain V2.

## Product Rule

A user has one business account. After that business is approved, the business
owner can manage one active store and publish store items for public browsing.

Business store items do not use the individual trade workflow. In MVP they are
public catalog items only:

- no platform payment
- no cart
- no checkout
- no order record
- no inventory reservation
- no shipping or fulfillment workflow
- no claim that the platform processed a transaction

The public store page may show item details and price, but purchase and
transaction actions are deferred until V2.

## Store Item Lifecycle

MVP business store items use a simple seller-owned lifecycle:

1. `DRAFT`: catalog staff is editing the item.
2. `ACTIVE`: item is complete and visible on `/stores`.
3. `PAUSED`: business hides the item from public browsing.
4. `REMOVED_BY_ADMIN`: platform removes the item from public browsing for
   safety or policy reasons.

Publishing from `DRAFT` to `ACTIVE` is a business seller action, not an admin
approval queue. The system still validates required fields, category, price,
safe media ownership, business membership, and active store/business status.

Do not fake admin approval for self-published store items. If the current
listing schema cannot distinguish admin-approved listings from business
self-published listings, add a forward-safe publication marker or moderation
source before implementation.

## Required Permissions

Allowed actors:

- active business owner
- future catalog manager/staff permission when BUS-07/V3 staff management is
  implemented

Checks:

- requester must be authenticated
- business must be approved and active
- requester must be an active member of the business
- store must belong to that business and be active
- all business and store IDs are server-validated
- client cannot set owner, business membership, status, publication source,
  moderation state, payment status, inventory, order, or transaction fields

## Seller Portal UX

Add a business seller portal store-item area:

```text
/seller/store/items
/seller/store/items/new
/seller/store/items/:listingId
```

Expected controls:

- item list with status filters: draft, active, paused, removed
- create item form
- edit item form
- image upload and ordering
- save draft
- publish to store
- pause item
- relist paused item

The seller portal should keep the existing dashboard style. It should not look
like public shopping pages.

## Public Store UX

`/stores` should show active store items as browse cards.

Public cards may show:

- image
- title
- price and currency
- store name
- condition
- city/region if available
- item detail link

Public cards must not show:

- buy now
- add to cart
- checkout
- inventory reservation
- order status
- payment status
- internal business IDs
- membership/staff data
- private contact information
- moderation/admin history

Use neutral copy such as "Store item" or "Available from store". Avoid
transaction language until the V2 payment/order flow exists.

## API Plan

Seller commands:

```text
GET    /api/v1/businesses/{businessId}/store/items
POST   /api/v1/businesses/{businessId}/store/items
GET    /api/v1/businesses/{businessId}/store/items/{listingId}
PATCH  /api/v1/businesses/{businessId}/store/items/{listingId}
POST   /api/v1/businesses/{businessId}/store/items/{listingId}/publish
POST   /api/v1/businesses/{businessId}/store/items/{listingId}/pause
POST   /api/v1/businesses/{businessId}/store/items/{listingId}/relist
```

Public reads:

```text
GET /api/v1/public/stores/listings/search
GET /api/v1/public/stores/{storeSlugOrId}/listings
```

Implementation may reuse the existing listing/media tables and public store
search response, but the public query must intentionally return active
self-published business store items. It must not depend on item-level admin
approval for business store items.

## Data Notes

Reuse `seller_type=BUSINESS`, `business_id`, and `store_id`.

Business item `quantity` is display/catalog quantity only in MVP. It is not an
authoritative inventory balance and cannot be used for reservation or
checkout until V2 `inventory_items` exists.

When V2 commerce begins, migrate or map active business store items into SKU
inventory records, then add cart, payment, checkout, order, and shipping
state machines around them. Do not add those state machines in this MVP plan.

## Implementation Slices

1. `BUS-LIST-01` Current business/store context.
   - Add or reuse an endpoint that tells the seller portal which approved
     business and store the current user can manage.
   - Keep one business account per user.
   - Status: complete. See
     `docs/mvp/bus/bus-list-01-current-business-store-context.md`.

2. `BUS-LIST-02` Store item draft and edit.
   - Add seller portal item list, create, read, and edit.
   - Enforce active business membership.
   - Require optimistic locking for edits.
   - Status: complete. See
     `docs/mvp/bus/bus-list-02-store-item-draft-edit.md`.

3.
   - Reuse the listing media upload/confirm/attach flow for business store
     items.
     - Keep media ownership and safe public delivery checks.
     - Status: complete. See
       `docs/mvp/bus/bus-list-03-store-item-media.md`.

4. `BUS-LIST-04` Self-publish to `/stores`.
   - Add publish, pause, and relist commands.
   - Update public store search to return active self-published store items.
   - Add frontend coverage that a published business item appears on
     `/stores` without admin approval.

5. `BUS-LIST-05` Reactive admin removal.
   - Preserve or add admin ability to remove active public store items with a
     reason.
   - Removal hides the item without deleting history.

## Acceptance Criteria

- Approved business owner can create a complete store item.
- Approved business owner can upload/attach item images.
- Approved business owner can publish the item without admin approval.
- Published item appears in `/stores`.
- Draft, paused, removed, and inactive-business items do not appear in
  `/stores`.
- Pending, rejected, or non-business users cannot create store items.
- Cross-business access returns `403`.
- Public responses do not expose internal business, membership, media storage,
  moderation, payment, inventory, order, or transaction fields.
- No payment transaction, cart, checkout, inventory reservation, order, or
  shipping behavior is implemented.

## Verification Plan

- Backend authorization tests for owner, non-member, cross-business, pending
  business, rejected business, and inactive store access.
- Backend lifecycle tests for draft, publish, pause, relist, and admin remove.
- Backend public search tests proving active self-published business items
  appear without listing approval.
- Frontend seller portal tests for item create/edit/publish status.
- Frontend `/stores` tests for visible active store item cards and no
  checkout/payment actions.
