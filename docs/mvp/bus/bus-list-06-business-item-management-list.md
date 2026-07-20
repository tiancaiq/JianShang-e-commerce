# BUS-LIST-06 Business Item Management List

Status: complete.

## Goal

Give an approved business seller one operational catalog screen for finding,
reviewing, and acting on their own store items.

The existing `/seller/store/items` route remains the management entry point.
This slice improves that route; it does not change the public `/stores`
business-item browse surface.

## Previous Baseline

- The seller portal already loads all business store items from
  `GET /api/v1/businesses/{businessId}/store/items`.
- Each response includes attached image metadata.
- Draft items can be published, active items can be paused, and paused items
  can be relisted with optimistic locking.
- The current table has no search, status filters, pagination, status counts,
  item thumbnails, or public preview link.
- The current table offers `Edit` for every status even though the business
  item lifecycle does not safely support active-item editing.

## Implemented Seller Workflow

1. Open `/seller/store/items`.
2. Scan catalog totals by `All`, `Draft`, `Active`, `Paused`, and `Removed`.
3. Search by item title or SKU and optionally select one status.
4. Open the edit form or run the valid lifecycle action for the item state.
5. Open the public item detail for an active item.
6. Return to the list with the current search and status selection preserved.

## Implemented API Contract

Added a backward-compatible management query:

```text
GET /api/v1/businesses/{businessId}/store/items/search
```

Query parameters:

- `q`: optional title or exact/partial SKU search
- `status`: optional `DRAFT`, `ACTIVE`, `PAUSED`, or `REMOVED_BY_ADMIN`
- `cursor`: optional opaque cursor
- `limit`: optional page size, with a server default and maximum

Response:

```json
{
  "data": [],
  "page": {
    "nextCursor": null,
    "hasMore": false
  },
  "summary": {
    "total": 0,
    "draft": 0,
    "active": 0,
    "paused": 0,
    "removed": 0
  }
}
```

Rules:

- Results are scoped to the current active business membership and active
  store context.
- The path business ID must match that context.
- Results sort by `updatedAt DESC, id DESC`.
- The cursor is opaque to clients and carries the stable sort position.
- Status summary counts describe the whole current business catalog and do
  not change when a status filter is selected.
- Search and status filters are applied server-side.
- The existing unpaged `GET /businesses/{businessId}/store/items` response is
  preserved so the contract is not broken.

## Lifecycle And Edit Rules

The management list exposes only actions supported by the current item state:

| Status | Seller actions |
| --- | --- |
| `DRAFT` | Edit, Publish |
| `ACTIVE` | View item, Pause |
| `PAUSED` | Edit, Relist |
| `REMOVED_BY_ADMIN` | None; show the admin removal reason |

To make this workflow safe and complete:

- active business items are read-only in the seller edit route and API;
- a seller pauses an active item before changing item fields or media;
- paused business items may be edited and remain `PAUSED`;
- relisting revalidates publish requirements before restoring public
  visibility;
- edit and lifecycle commands continue to require `If-Match`;
- an action response replaces the affected row and refreshes summary counts;
- cross-business item access remains `403`.

This closes the current unsafe path where editing an active item can remove its
search projection without first changing the item out of `ACTIVE`.

## Seller Portal UI

The route remains a quiet management dashboard rather than a public shopping
page.

- Header: store name, short catalog label, and one `New item` command.
- Catalog status rail: a compact segmented control for `All`, `Draft`,
  `Active`, and `Paused`, including server-provided counts.
- Filter row: title/SKU search, clear control, and result count.
- Desktop: dense item table with image, title, SKU, catalog quantity, price,
  status, updated date, and state-aware actions.
- Mobile: each table row becomes a compact stacked management row without
  horizontal overflow.
- Item image: first attached image when available, otherwise a restrained
  placeholder.
- Active item title or `View item` action routes to `/listings/{listingId}`.
- Search, status, and cursor-free filter state use URL query parameters so
  browser back navigation restores the list view.
- Loading, API error, no-store, empty-catalog, and no-filter-results states
  each provide a specific next action.
- Keyboard focus, button disabled state, status text, and image alt text remain
  visible and accessible.

The visual signature is the catalog status rail: it makes item lifecycle state
the primary way sellers orient themselves while the rest of the page stays
compact and work-focused. Existing seller portal theme tokens and typography
remain authoritative; this slice does not introduce a separate visual system.

## Implemented Backend Work

- Add the business item search request parsing and paged response DTOs.
- Add business-scoped repository queries for page data and status summary.
- Reused the existing `(business_id, status, updated_at)` index; no migration
  was required.
- Enforce business-specific editable states so `DRAFT` and `PAUSED` are
  mutable and `ACTIVE` is not.
- Allow business-owned paused item media changes while continuing to reject
  active item media changes.
- Keep structured authorization-denial and lifecycle-failure logging.

## Implemented Frontend Work

- Add management search request/page/summary models and service coverage.
- Replace the all-at-once item load with the paged management query.
- Add URL-backed search and status filter state.
- Add the status rail, item thumbnails, responsive rows, load-more behavior,
  and state-specific empty/error UI.
- Render actions from the lifecycle table above.
- Update the business item form so paused business items are editable and
  active business items are read-only.
- Reuse the shared status-pill and empty-state primitives where they fit the
  seller portal.

## Authorization And Safety

- Only an authenticated member with business listing permission may query or
  act on the catalog.
- Every query and item command is scoped by the current business/store context.
- The client cannot choose another owner, store, publication source,
  moderation state, or lifecycle status.
- Public preview uses the existing safe public listing detail response.
- Item quantity remains display/catalog quantity only in MVP.

## Acceptance Criteria

- An approved business seller sees only items owned by the current business
  store.
- Search matches the seller's item title or SKU.
- Status selection returns only the selected state and summary counts remain
  catalog-wide.
- Cursor pagination returns stable, non-duplicated pages.
- Draft rows show `Edit` and `Publish`.
- Active rows show `View item` and `Pause`, with no edit action.
- Paused rows show `Edit` and `Relist`.
- Paused item fields and media can be edited without making the item public.
- Active item field and media edits are rejected by the backend.
- Lifecycle actions update the row without reloading the whole page.
- Loading, no-store, no-items, no-results, and API-error states are covered.
- The route works at desktop and mobile widths without incoherent overlap or
  horizontal page scrolling.
- No admin moderation/removal controls appear in the seller portal.
- Removed items remain visible to the owning business with the recorded admin
  reason and no lifecycle actions.
- No cart, checkout, payment, authoritative inventory, order, shipping,
  fulfillment, or notification behavior is added.

## Verification

- Product-service integration tests passed:
  - current-business isolation and cross-business denial;
  - title/SKU and status filtering;
  - stable cursor pagination;
  - catalog summary counts;
  - active edit/media rejection;
  - paused edit/media success followed by relist validation.
- Frontend service tests passed for query parameter and response mapping.
- Frontend component tests passed for filter state, status counts, thumbnails,
  load-more, action visibility, action updates, and empty/error states.
- Listing form tests passed for business-specific active and paused
  editability.
- Angular production build passed.
- Browser walkthrough passed at 1280px and 390px using an approved business
  account, including thumbnail delivery, lifecycle counts, active-row action
  visibility, stacked mobile layout, and horizontal-overflow checks.

## Explicitly Out Of Scope

- Admin removal controls, review queues, reinstatement, and appeals. Seller
  visibility of an existing removal reason is included.
- Changes to the public `/stores` item-card design or public search ranking.
- Publish-readiness checklist UX beyond existing validation messages.
- Bulk edit, bulk publish, CSV import, duplicate-item, and catalog analytics.
- V2 cart, checkout, payment, inventory reservation, orders, shipping,
  fulfillment, and notifications.
