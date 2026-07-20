# V2-INV-01 Business Inventory Foundation Plan

Status: complete and browser verified on 2026-07-17.

Release: V2.

Requirement: `INV-01`.

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/bus/bus-list-00-store-item-self-publishing-plan.md`
- `docs/mvp/bus/bus-list-06-business-item-management-list.md`

Implementation result:

- Activated `inventory-service` in the parent Maven build and gateway.
- Added forward-only Flyway migration
  `V2__create_business_inventory_foundation.sql`.
- Added tenant-scoped initialization, balance, adjustment, movement, optimistic
  locking, idempotency, and transactional outbox behavior.
- Added owner/manager inventory permissions and service-authenticated product
  commerce-context reads.
- Added `/seller/inventory`, catalog Inventory actions, and neutral public
  business-item availability wording.
- Verified the migration and command flow against MySQL 8.4, ran focused
  Angular tests and production build, and completed the approved-account
  browser walkthrough through the gateway.

## 1. Goal

Give an authorized business owner or manager an audited, tenant-scoped source
of truth for on-hand stock attached to a business store item.

The completed implementation will let the business:

1. Open a seller-portal inventory page.
2. Select an eligible business store item.
3. Explicitly initialize its authoritative on-hand quantity.
4. Set or adjust that quantity with a required reason.
5. Review the resulting append-only movement history.
6. See on-hand, reserved, and available balances without confusing the old
   catalog quantity with inventory.

Every successful initialization or adjustment creates exactly one movement.
No command can make available quantity negative.

## 2. Slice Boundary

Included:

- Rebuild and activate `inventory-service` against approved V2 contracts.
- Inventory item and movement persistence.
- Explicit initialization from an active or paused business store item.
- Set and signed-adjust operations.
- Optimistic locking and idempotency.
- Business membership and permission enforcement.
- Seller inventory list, initialization, adjustment, and history UI.
- A product-service internal commerce-context read for safe listing validation.
- Removal of public wording that presents catalog quantity as available stock.
- Transactional inventory outbox records and safe operational telemetry.

Not included:

- Cart or add-to-cart actions.
- Inventory reservations.
- Reservation expiry, release, or commit.
- Checkout, payment, orders, refunds, fulfillment, or shipping.
- Bulk import, warehouse/bin/location tracking, purchase orders, suppliers,
  reorder rules, or analytics.
- Seller-defined business staff roles beyond the existing owner/manager model.
- Public low-stock counts or exact public on-hand quantity.

The `reserved` column exists at zero for forward compatibility with
V2-INV-02. No V2-INV-01 browser or public API can change it.

## 3. Current Repository Reality

The archived `inventory-service` is tutorial code:

- `t_inventory` has an auto-increment ID, SKU, and mutable quantity only.
- Stock is looked up globally by SKU.
- There is no listing ID, business scope, movement ledger, version,
  idempotency, authorization, or standard error envelope.
- The route is `/api/inventory`, not `/api/v1`.
- `V1__init.sql` inserts tutorial phone data.

That model is not migrated into the V2 aggregate and must not be restored to
the active build unchanged.

Implementation rules:

- Keep the existing `V1__init.sql` immutable because it may have run.
- Add forward-only migrations for the approved tables.
- Leave `t_inventory` quarantined and unused in V2-INV-01.
- Remove or archive `t_inventory` only in a later cleanup migration after an
  explicit data audit.
- Replace the tutorial entity, repository, service, controller, and tests.

## 4. Domain Ownership

Inventory service owns:

- On-hand quantity
- Reserved quantity
- Available-to-sell calculation
- Inventory aggregate version
- Initialization and adjustment commands
- Append-only movement history
- Inventory idempotency records
- Inventory outbox events

Product service remains authoritative for:

- Listing ID and business/store ownership
- Seller type
- Current SKU and title
- Listing status and publication source
- Catalog price and currency
- The legacy catalog quantity suggestion

Auth service remains authoritative for:

- Current user identity
- Active business membership
- Business status
- Store status
- Inventory permissions

Inventory service never updates catalog, identity, membership, or store
tables. Product and auth services never update inventory tables.

## 5. Inventory Identity And Catalog Quantity

`listingId` is the stable inventory identity. SKU is copied as a catalog
snapshot for display and audit, but it is not the inventory primary key.

Rules:

- One inventory item exists at most once for a listing.
- The listing must have `sellerType=BUSINESS`.
- The requested `businessId` must match the listing business.
- SKU changes do not create a second inventory row or move balances.
- Current title, SKU, listing status, and catalog version are read from product
  service when seller UI needs current catalog context.
- Inventory commands never use the client-supplied SKU to select stock.

The existing listing `quantity` remains a legacy catalog suggestion:

- It is not copied automatically into on-hand stock.
- The initialization UI may display it as a suggestion.
- The seller must explicitly confirm the initial on-hand quantity.
- Changing catalog quantity never changes inventory.
- Public `/stores` UI stops labeling catalog quantity as "available".
- Exact public availability remains deferred to V2-CART-02 validation.

## 6. Eligibility Rules

Initialization is allowed only when:

- The actor is authenticated.
- The actor has an active membership in the requested active business.
- The active business store belongs to that business.
- The actor has `INVENTORY_MANAGE`.
- Product service confirms the listing belongs to the business.
- The listing is a business store item.
- The listing has a nonblank SKU.
- The listing status is `ACTIVE` or `PAUSED`.
- No inventory item already exists for that listing.

`DRAFT`, `REMOVED_BY_ADMIN`, `SOLD`, `CLOSED`, rejected, individual, missing,
and cross-business listings cannot be initialized.

An already initialized item can be adjusted while its listing is `ACTIVE` or
`PAUSED`. Pausing a listing does not erase or reset its inventory. A removed or
closed listing keeps its immutable inventory history but rejects new seller
adjustments until it becomes eligible again through an approved catalog
transition.

## 7. Inventory Aggregate

The aggregate exposes:

```text
inventoryItemId
businessId
listingId
skuSnapshot
catalogVersionSnapshot
onHand
reserved
available
version
initializedAt
createdAt
updatedAt
```

Invariant:

```text
available = onHand - reserved
onHand >= 0
reserved >= 0
reserved <= onHand
```

During V2-INV-01:

```text
reserved = 0
available = onHand
```

The database stores `on_hand` and `reserved`. `available` is calculated and
never accepted as a client write.

## 8. Commands And Movements

### 8.1 Initialize

Input:

- Initial on-hand quantity, including zero
- Optional safe note
- `Idempotency-Key`

Behavior:

1. Authorize the actor for the business.
2. Load product commerce context through a service-authenticated API.
3. Validate listing type, business, SKU, and status.
4. Check idempotency before duplicate/state validation.
5. Insert the inventory item with `reserved=0` and `version=0`.
6. Insert one `INITIALIZE` movement with before `0`, after initial on-hand, and
   the derived delta.
7. Insert an `inventory.initialized` outbox event in the same transaction.
8. Store the command result for idempotent replay.

Replaying the same key and request returns the original `201` response.
Initializing an existing item with another key returns
`409 INVENTORY_ALREADY_INITIALIZED`.

### 8.2 Set or adjust on-hand

The request contains exactly one operation:

- `SET`: replace on-hand with a non-negative absolute quantity.
- `ADJUST`: apply a nonzero signed delta.

Both operations require:

- A stable reason code
- Optional safe note
- `If-Match` with the current inventory version
- `Idempotency-Key`

Initial reason codes:

- `RESTOCK`
- `STOCK_COUNT_CORRECTION`
- `DAMAGED`
- `LOST`
- `RETURNED`
- `OTHER`

The movement stores the command operation, reason, signed derived delta,
before/after balances, reserved snapshot, actor, and correlation data.

The command transaction:

1. Returns a prior result when the same idempotency key and request hash were
   already completed.
2. Verifies current product eligibility and business authorization.
3. Verifies `If-Match`.
4. Calculates the next on-hand quantity using overflow-safe arithmetic.
5. Rejects a result below `reserved`.
6. Updates the aggregate and increments its version.
7. Inserts exactly one movement.
8. Inserts one `inventory.adjusted` outbox event.
9. Stores the idempotent result.

Idempotency lookup occurs before version validation. A network retry of a
successful command therefore returns its original response even if the
aggregate version has since advanced.

## 9. API Contract

All routes use `/api/v1`, the standard data/error envelopes, correlation IDs,
gateway session authentication, and CSRF protection for browser writes.

### 9.1 Seller inventory routes

```text
GET  /businesses/{businessId}/inventory?q=&listingStatus=&cursor=&limit=
POST /businesses/{businessId}/inventory/{listingId}/initialize
GET  /businesses/{businessId}/inventory/{listingId}
POST /businesses/{businessId}/inventory/{listingId}/adjustments
GET  /businesses/{businessId}/inventory/{listingId}/movements?cursor=&limit=
```

The inventory list is a read composition:

1. Product service supplies the current business item page, search result,
   catalog status, title, SKU, and cursor.
2. Inventory service loads matching inventory rows by listing ID.
3. Inventory service returns each catalog item with nullable inventory
   balances and an `INITIALIZED` or `NOT_INITIALIZED` inventory state.

This makes active/paused items discoverable for initialization without browser
N+1 requests. Catalog `updated_at DESC, id DESC` remains the stable page order.
Inventory detail and movement routes remain inventory-owned reads.

Initialize request:

```json
{
  "onHand": 12,
  "note": "Opening stock count"
}
```

Adjustment request:

```json
{
  "operation": "ADJUST",
  "quantity": -2,
  "reason": "DAMAGED",
  "note": "Damaged during shelf count"
}
```

For `SET`, `quantity` is the desired absolute on-hand quantity. For `ADJUST`,
it is the signed delta.

Inventory response:

```json
{
  "data": {
    "id": "01...",
    "businessId": "01...",
    "listingId": "01...",
    "skuSnapshot": "SKU-100",
    "onHand": 10,
    "reserved": 0,
    "available": 10,
    "version": 1,
    "initializedAt": "timestamp",
    "updatedAt": "timestamp"
  }
}
```

Movement history is ordered by `created_at DESC, id DESC` and uses opaque
cursors.

### 9.2 Internal product commerce context

```text
GET /internal/businesses/{businessId}/store/items/commerce-context?q=&status=&cursor=&limit=
GET /internal/businesses/{businessId}/store/items/{listingId}/commerce-context
```

These routes require service authentication and return only the catalog facts
needed by commerce:

- listing, business, and store IDs
- seller type
- title and SKU
- listing status and publication source
- price and currency for later cart reuse
- catalog quantity suggestion
- catalog version

Inventory service treats a missing or mismatched result as ineligible. The
collection route preserves the product-service cursor and is used only to
compose seller inventory reads. The browser cannot call either internal route
directly.

## 10. Error Contract

| Status | Code | Meaning |
|---|---|---|
| `403` | `BUSINESS_INVENTORY_FORBIDDEN` | Actor lacks active scoped permission |
| `404` | `INVENTORY_NOT_FOUND` | No item in the authorized business scope |
| `404` | `BUSINESS_LISTING_NOT_FOUND` | Product context is absent or intentionally hidden |
| `409` | `INVENTORY_ALREADY_INITIALIZED` | Listing already has an inventory aggregate |
| `409` | `INVENTORY_VERSION_CONFLICT` | `If-Match` is stale |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Same key has a different request hash |
| `422` | `INVENTORY_LISTING_INELIGIBLE` | Listing type/status/SKU is not eligible |
| `422` | `INVENTORY_INVALID_ADJUSTMENT` | Operation, quantity, or reason is invalid |
| `422` | `INVENTORY_AVAILABLE_WOULD_BE_NEGATIVE` | New on-hand is below reserved |
| `503` | `INVENTORY_DEPENDENCY_UNAVAILABLE` | Required auth/product validation is unavailable |

Error messages do not disclose another business's inventory or listing
details.

## 11. Database Plan

Add a forward-only inventory-service migration after legacy `V1__init.sql`.

### 11.1 `inventory_items`

| Column | Rule |
|---|---|
| `id` | `CHAR(26)` canonical ULID primary key |
| `business_id` | Required business reference |
| `listing_id` | Required globally stable listing reference |
| `sku_snapshot` | Required SKU copied at initialization |
| `catalog_version_snapshot` | Product version used during initialization |
| `on_hand` | Non-negative integer |
| `reserved` | Non-negative integer, initially zero |
| `version` | Optimistic version, initially zero |
| `initialized_at` | UTC |
| `created_at`, `updated_at` | UTC |

Constraints and indexes:

- Unique `listing_id`
- Check `on_hand >= 0`
- Check `reserved >= 0`
- Check `reserved <= on_hand`
- Index `(business_id, updated_at, id)`
- Nonunique index `(business_id, sku_snapshot)`

SKU is not unique in the inventory schema because it is a catalog snapshot.
Product service remains responsible for current per-business SKU uniqueness.

### 11.2 `inventory_movements`

| Column | Rule |
|---|---|
| `id` | `CHAR(26)` canonical ULID primary key |
| `inventory_item_id` | Inventory aggregate foreign key |
| `business_id`, `listing_id` | Scoped immutable references |
| `operation` | `INITIALIZE`, `SET`, or `ADJUST` |
| `reason_code` | Stable reason |
| `quantity_delta` | Signed derived change |
| `on_hand_before`, `on_hand_after` | Auditable balances |
| `reserved_snapshot` | Reserved balance at command time |
| `note` | Nullable bounded safe text |
| `actor_user_id` | Authenticated actor |
| `command_id` | Unique command/result reference |
| `correlation_id` | Request trace |
| `created_at` | UTC |

Movements are append-only. Seller commands cannot update or delete them.

Indexes:

- Unique `command_id`
- `(inventory_item_id, created_at, id)`
- `(business_id, created_at, id)`

### 11.3 Supporting tables

Inventory service also owns:

- `idempotency_records` with unique caller/operation/key scope, request hash,
  status, result reference, and retained response.
- `outbox_events` with event ID, aggregate, event type/version, payload,
  correlation/causation IDs, publication state, and retry data.

All item, movement, idempotency, and outbox writes for one command are in one
MySQL transaction.

## 12. Authorization Plan

Add these permissions to the existing auth-service membership response:

- `INVENTORY_VIEW`
- `INVENTORY_MANAGE`

Initial role mapping:

| Role | Permissions |
|---|---|
| `OWNER` | View and manage inventory |
| `MANAGER` | View and manage inventory |
| Other/future staff | No inventory access until explicitly granted |

Every seller route:

- Derives the actor from authentication.
- Validates active membership and active business/store status.
- Requires the permission appropriate to the route.
- Applies the path `businessId` to every repository query.
- Never accepts actor user ID, reserved quantity, available quantity, version,
  listing ownership, or business ownership in the JSON body.

Cross-business authorization, cross-business listing initialization, and
cross-business inventory reads are mandatory integration tests.

## 13. Seller Portal Plan

Add:

```text
/seller/inventory
```

The route lives under the existing seller dashboard and resolves the user's
single approved business/store context. The backend API still uses explicit
`businessId`.

Navigation adds an Inventory entry only when V2-INV-01 is enabled.

Inventory page:

- Compact management table, not marketplace cards
- Current item title and SKU from product data
- Catalog lifecycle status
- On-hand, reserved, and available columns
- Last updated time
- Search/pagination consistent with current business item management
- Empty state linking to active/paused store items that can be initialized
- Clear zero-stock state
- Adjustment history action

Initialization:

- Uses the existing business item search as the item picker
- Allows active and paused items only
- Shows catalog quantity as an optional suggestion, clearly labeled
- Requires the seller to enter/confirm initial on-hand
- Supports zero as a valid out-of-stock initialization

Adjustment:

- Uses a focused dialog or panel
- Segmented control for Set versus Adjust
- Numeric input with signed behavior for Adjust
- Required reason menu
- Optional note
- Displays the resulting on-hand value before confirmation
- Refreshes aggregate version after success
- Treats version conflict as a reload-required state, not a generic error

The existing store item list replaces its "catalog qty" stock presentation
with an Inventory action/state. Public `/stores` removes the text that labels
catalog quantity as available. Public buy/cart controls remain absent.

## 14. Events And Observability

Outbox event types:

- `inventory.initialized.v1`
- `inventory.adjusted.v1`

Payload contains:

- event and aggregate IDs
- business and listing IDs
- movement ID
- on-hand, reserved, and available
- inventory version
- operation and reason
- occurred-at and correlation metadata

Do not include seller notes, access tokens, or unrestricted user PII in event
payloads.

Structured logs:

- initialization success/conflict
- adjustment success/version conflict
- authorization denial
- ineligible listing
- negative-availability rejection
- idempotency replay/conflict
- product/auth dependency failure
- outbox publication failure

Metrics:

- initialized item count
- adjustment count by operation/reason
- version conflict count
- rejected negative-availability count
- dependency failure count
- outbox backlog age

## 15. Implementation Sequence

1. Replace archived domain and add forward-only inventory migrations.
2. Add standard correlation/error/security configuration and configuration
   validation.
3. Add auth-service inventory permissions.
4. Add product-service internal commerce-context read.
5. Implement initialization, read, list, adjustment, and movement APIs.
6. Add idempotency and transactional outbox behavior.
7. Add gateway routing and activate `inventory-service` in the parent build/CI.
8. Add Angular inventory models/service and `/seller/inventory`.
9. Replace catalog/public quantity wording that implies authoritative stock.
10. Complete backend, frontend, authorization, concurrency, and browser tests.

The service and UI stay feature-flagged until the complete slice passes
verification. Partial inventory APIs must not make `/stores` appear
purchasable.

## 16. Test Plan

Domain/unit:

- Initialize with zero and positive on-hand
- Reject negative initialization
- Set and signed adjust calculations
- Reject zero adjustment
- Reject overflow
- Derive movement delta and before/after balances
- Preserve `reserved <= onHand`

MySQL integration:

- Forward migration succeeds after existing V1
- Unique listing inventory
- Item, movement, idempotency, and outbox atomicity
- Append-only movement behavior
- Cursor ordering for inventory and movements
- Stale `If-Match` conflict
- Same idempotency key returns original result
- Different request with same key conflicts
- Concurrent commands with one version allow one winner

Authorization/integration:

- Owner and manager access
- Missing permission denied
- Pending/rejected/suspended business denied
- Inactive membership denied
- Cross-business list/detail/adjust denied
- Cross-business listing initialization denied
- Individual listing initialization rejected
- Draft/removed/closed listing initialization rejected
- Product/auth dependency failure returns safe `503`

Frontend:

- Route and navigation feature flag
- Loading, empty, error, and populated list states
- Initialize with explicit seller confirmation
- Set and adjust validation
- Version conflict reload state
- Movement history rendering
- Catalog quantity is not labeled as available inventory
- Public `/stores` does not claim catalog quantity is available stock

Browser walkthrough:

1. Sign in as an approved business owner.
2. Open the business item management list.
3. Open Inventory and initialize an active item.
4. Verify one movement and matching balances.
5. Apply a negative adjustment with a reason.
6. Reload and verify persistence/version/history.
7. Attempt an invalid negative result and verify no movement was added.
8. Verify `/stores` still has no cart/checkout controls and no catalog-quantity
   availability claim.

## 17. Acceptance Criteria

- Authorized owner/manager can initialize one inventory item for an eligible
  business listing.
- Initialization never silently copies the catalog quantity.
- One listing cannot have two inventory aggregates.
- Authorized owner/manager can set or adjust on-hand with a reason.
- Every successful command creates exactly one append-only movement.
- On-hand, reserved, and available are server-derived and non-negative.
- Stale versions and idempotency conflicts are explicit.
- Network retries do not duplicate movements.
- Cross-business and individual-listing access is denied.
- Current SKU changes cannot create or select a second inventory balance.
- Seller UI distinguishes catalog data from authoritative inventory.
- Public store UI does not present catalog quantity as available stock.
- Cart, reservation, checkout, payment, order, and shipping behavior remains
  absent.

## 18. Deferred Work

- V2-CART-01 owns cart storage and buyer cart UI.
- V2-CART-02 owns source revalidation and public availability warnings.
- V2-INV-02 owns reserve, expire, release, commit, and oversell concurrency.
- V2-CHK-01 owns checkout snapshots and reservation orchestration.
- V2-ORD-03 owns cancellation-related stock compensation.
- V2-SHP-01 owns fulfillment shipment quantities.
- Bulk inventory operations and multi-location stock require a separate
  approved requirement.
