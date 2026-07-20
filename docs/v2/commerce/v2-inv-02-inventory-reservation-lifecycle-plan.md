# V2-INV-02 Inventory Reservation Lifecycle Plan

Status: complete; live lifecycle and cart-boundary verified on 2026-07-18.

Release: V2.

Requirements:

- `INV-02`
- `INV-03`
- `INV-04`

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Depends on:

- `V2-INV-01`
- `V2-CART-02`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- `docs/v2/commerce/v2-inv-01-business-inventory-foundation-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`

## 1. Goal

Give trusted commerce orchestration an atomic, durable inventory reservation
primitive for one checkout.

The slice must:

- Reserve every requested line or reserve none.
- Prevent concurrent buyers from overselling.
- Expire or explicitly release active reservations exactly once.
- Commit a paid reservation exactly once.
- Preserve `available = onHand - reserved` throughout every transition.
- Produce durable audit and recovery signals for terminal transitions.

The slice does not expose checkout to buyers. It prepares the internal
inventory contract that V2-CHK-01, V2-PAY-01, and V2-ORD-01 will call later.

## 2. Contract Decisions

The earlier documents describe a reservation as storing checkout, item,
quantity, status, and expiry, but they do not define whether one checkout
uses one reservation or one reservation per line.

V2-INV-02 resolves that ambiguity:

- One reservation is a checkout-scoped aggregate.
- One reservation contains from 1 through 50 distinct listing lines.
- Reserve is one all-or-nothing MySQL transaction across all lines.
- Commit, release, and expiry transition the whole reservation.
- The API returns one reservation ID for later commands.

Independent per-line reservation calls are rejected because they could leave a
checkout partially reserved after a process or network failure.

Expiry policy remains separated from inventory mechanics:

- The reserve request supplies an absolute `expiresAt`.
- Inventory service verifies that it is in the future and within a configured
  maximum lifetime.
- V2-CHK-01 will choose the checkout lifetime and use the same deadline for
  checkout and reservation.
- No checkout lifetime is hard-coded by this slice.

Reservation purpose is explicit:

- `CHECKOUT`: the normal pre-payment reservation.
- `PAYMENT_RECOVERY`: the one recovery attempt described by V2-COM-00 after a
  verified payment finds an expired reservation.

Only `CHECKOUT` is exercised by a buyer workflow in V2-CHK-01.
`PAYMENT_RECOVERY` is forward-safe contract support for V2-ORD-01.

## 3. Slice Boundary

Included:

- Token-protected internal reserve, get, release, and commit APIs.
- A checkout-scoped reservation aggregate with immutable item lines.
- Atomic multi-line reservation with deterministic row-lock ordering.
- Explicit release and scheduled expiry.
- Commit that reduces both on-hand and reserved quantities.
- Command idempotency and request-hash conflict detection.
- Reservation transition history and transactional outbox records.
- Durable recovery signal when commit targets an expired or released
  reservation.
- Structured logs, metrics, configuration validation, and focused tests.
- Browser observation through the existing seller inventory page.

Not included:

- Buyer checkout routes, checkout persistence, or a checkout button.
- Address, price, tax, shipping, discount, or policy snapshots.
- Payment-provider intent or webhook handling.
- Order creation, refund execution, or an admin recovery queue.
- Reservation extensions or expiry changes after creation.
- Partial commit or partial release.
- Seller-created reservations or reservation management UI.
- Public reservation IDs or reservation state on `/stores`.
- Individual listings or changes to the individual trade state machine.

V2-CHK-01 owns reservation orchestration and matching checkout persistence.
V2-ORD-01 owns payment-success recovery orchestration and creation of an
operations item from a recovery-required result.

## 4. Current Repository Reality

V2-INV-01 already provides:

- `inventory_items` with on-hand, reserved, available, and optimistic version.
- The invariant `0 <= reserved <= on_hand`.
- Seller initialization and adjustments.
- Seller adjustment rejection when the requested on-hand would fall below
  reserved stock.
- Inventory idempotency and outbox tables.
- Service-token-protected availability reads.
- MySQL Testcontainers coverage.
- A seller inventory page that displays on-hand, reserved, and available.

V2-CART-02 already provides point-in-time product, seller/store, price,
currency, and availability validation. It does not reserve inventory.

Missing behavior:

- Reservation persistence and item lines.
- Atomic reserved-balance increments across several items.
- Reservation state transitions and transition history.
- Expiry scanning.
- Commit-time on-hand reduction.
- Reservation-specific idempotency, errors, events, and concurrency tests.

The existing V2 migration must remain immutable. V2-INV-02 adds a new
forward-only migration.

## 5. State Model And Invariants

Reservation state:

```text
ACTIVE -> COMMITTED
ACTIVE -> RELEASED
ACTIVE -> EXPIRED
```

There are no transitions out of a terminal state.

Aggregate invariants:

- A reservation has at least one and at most 50 lines.
- Listing IDs are distinct within the reservation.
- Every quantity is from 1 through 999.
- Every line references an initialized inventory item.
- The inventory item's listing ID is the stock identity.
- All item balance changes for one transition occur in one transaction.
- An `ACTIVE` reservation contributes its quantities to `reserved`.
- `RELEASED` and `EXPIRED` reservations contribute nothing to `reserved`.
- `COMMITTED` quantities have been removed from both `on_hand` and `reserved`.
- `available` never becomes negative.
- Reservation expiry is immutable after creation.
- One terminal transition and one matching outbox event are recorded at most
  once.

Derived usability:

```text
usable = status == ACTIVE && now < expiresAt
```

An active row past its deadline is not committable even if the expiry worker
has not processed it yet.

## 6. Internal API Contract

All routes use the `/api/v1` prefix and require
`X-Internal-Service-Token`. Browser and gateway routes do not expose them.

```text
POST /api/v1/internal/inventory/reservations
GET  /api/v1/internal/inventory/reservations/{reservationId}
POST /api/v1/internal/inventory/reservations/{reservationId}/release
POST /api/v1/internal/inventory/reservations/{reservationId}/commit
```

Every POST command requires `Idempotency-Key`. GET does not.

### 6.1 Reserve

Request:

```json
{
  "checkoutId": "01...",
  "purpose": "CHECKOUT",
  "expiresAt": "2026-07-18T15:30:00Z",
  "items": [
    {"listingId": "01...", "quantity": 2},
    {"listingId": "01...", "quantity": 1}
  ]
}
```

Rules:

- `checkoutId`, `expiresAt`, and a nonempty item list are required.
- `purpose` is `CHECKOUT` or `PAYMENT_RECOVERY`.
- Listing IDs must be distinct and canonical.
- No business ID, seller ID, price, total, currency, on-hand, reserved,
  available, or inventory version is accepted.
- A checkout and purpose can create at most one reservation.
- `201` returns only after the reservation, balance changes, history,
  idempotency result, and outbox event are durable.
- The same key and request returns the original `201`.
- The same key with a different canonical request hash returns `409`.
- Insufficient or missing stock fails the entire request without changing any
  balance.

### 6.2 Read

GET returns reservation state, purpose, checkout reference, timestamps, and
immutable requested lines plus current inventory balances.

The response includes:

```json
{
  "id": "01...",
  "checkoutId": "01...",
  "purpose": "CHECKOUT",
  "status": "ACTIVE",
  "usable": true,
  "expiresAt": "2026-07-18T15:30:00Z",
  "committedAt": null,
  "releasedAt": null,
  "releaseReason": null,
  "version": 0,
  "items": [{
    "inventoryItemId": "01...",
    "businessId": "01...",
    "listingId": "01...",
    "quantity": 2,
    "onHand": 9,
    "reserved": 2,
    "available": 7,
    "inventoryVersion": 2
  }]
}
```

Current balances are informational and may include later seller adjustments or
other reservations. Requested quantity and reservation state remain immutable
audit facts.

### 6.3 Release

Request:

```json
{"reason": "CHECKOUT_CANCELLED"}
```

Initial explicit reasons:

- `CHECKOUT_CANCELLED`
- `CHECKOUT_FAILED`
- `PAYMENT_FAILED`
- `SYSTEM_RECOVERY`

Behavior:

- Active and unexpired becomes `RELEASED`.
- Active but past deadline becomes `EXPIRED`, regardless of request reason.
- Reserved quantities are decremented once.
- Repeating release after `RELEASED` or `EXPIRED` returns the current
  reservation and does not change balances or add another transition.
- Release after `COMMITTED` returns
  `409 INVENTORY_RESERVATION_ALREADY_COMMITTED`.

The expiry worker uses `EXPIRY` as its internal transition reason and does not
call the HTTP route.

### 6.4 Commit

Commit has no request body. The trusted order-service caller is responsible
for invoking it only after verified payment success.

Behavior:

- Active and unexpired becomes `COMMITTED`.
- For every line, both `on_hand` and `reserved` decrease by the reserved
  quantity.
- Available quantity is unchanged by commit.
- A repeated commit after `COMMITTED` returns the current reservation and does
  not change balances or add another transition.
- Active but past deadline is first durably expired and then returns
  `409 INVENTORY_RESERVATION_EXPIRED`.
- Commit after `RELEASED` returns
  `409 INVENTORY_RESERVATION_NOT_COMMITTABLE`.
- Expired or released commit failures include `recoveryRequired=true` and
  create one durable `inventory.reservation.commit-rejected.v1` signal.

The failure signal does not create an admin case in this slice. V2-ORD-01
turns it into payment recovery or refund-required orchestration.

## 7. Reserve Transaction

Reserve uses one local MySQL transaction:

1. Validate service authentication, headers, IDs, quantities, item limit,
   distinct listing IDs, purpose, and expiry bounds.
2. Canonicalize request hashing by sorting lines by listing ID.
3. Return a completed idempotency result when the key and hash match.
4. Reject an existing checkout/purpose reservation created with another key.
5. Lock every referenced `inventory_items` row in ascending listing-ID order
   with `SELECT ... FOR UPDATE`.
6. Verify every item exists and has `on_hand - reserved >= requested`.
7. Insert the reservation header and immutable item lines.
8. Increment each item's `reserved` and `version`.
9. Insert the `CREATED -> ACTIVE` history entry.
10. Insert `inventory.reservation.created.v1` in the outbox.
11. Store the idempotent response.
12. Commit once.

If any line fails, no header, line, history, outbox, idempotency success, or
balance change is committed.

Sorted lock acquisition prevents multi-item checkouts from deadlocking solely
because callers supplied items in different orders.

## 8. Release And Expiry

Terminal transitions lock in this order:

1. Reservation header.
2. Reservation lines ordered by listing ID.
3. Referenced inventory items ordered by listing ID.

Release decrements each item's `reserved`, increments its version, records the
terminal transition, writes the outbox event, and stores the idempotency
result in one transaction.

Expiry uses a configurable scheduled worker:

- Query a bounded batch where `status=ACTIVE AND expires_at <= now`.
- Use `FOR UPDATE SKIP LOCKED` so multiple workers do not process the same
  reservation.
- Transition each claimed reservation to `EXPIRED`.
- Restore availability by decrementing reserved quantities.
- Emit `inventory.reservation.expired.v1`.
- Continue in later batches without an unbounded transaction.

Worker interval, batch size, enabled state, and maximum reservation lifetime
are validated configuration. Tests inject a controllable clock; production
logic does not depend on sleeps.

## 9. Commit And Recovery Safety

Commit locks the same rows as release and applies:

```text
nextOnHand = onHand - reservedLineQuantity
nextReserved = reserved - reservedLineQuantity
```

Both values are checked before update even though the database constraints
also protect them.

Commit-time rules:

- Deadline comparison happens while the reservation row is locked.
- A deadline that has passed wins over commit.
- COMMITTED replay is successful even when a different idempotency key is
  supplied, because the business effect already exists.
- RELEASED or EXPIRED never returns false success.
- Recovery-required signaling is deduplicated by reservation and failure
  reason, independent of the caller's retry key.

No path recreates stock after commit. Cancellation compensation belongs to
V2-ORD-03 and must use a later explicit inventory command.

## 10. Database Plan

Add:

```text
inventory-service/src/main/resources/db/migration/
V3__create_inventory_reservation_lifecycle.sql
```

### 10.1 `inventory_reservations`

| Column | Rule |
|---|---|
| `id` | `CHAR(26)` canonical ULID primary key |
| `checkout_id` | Required immutable order-service reference |
| `purpose` | `CHECKOUT` or `PAYMENT_RECOVERY` |
| `status` | `ACTIVE`, `COMMITTED`, `RELEASED`, or `EXPIRED` |
| `expires_at` | Required immutable UTC deadline |
| `committed_at` | Nullable terminal timestamp |
| `released_at` | Nullable release/expiry timestamp |
| `release_reason` | Nullable stable reason |
| `version` | Reservation transition version |
| `created_at`, `updated_at` | UTC |

Constraints and indexes:

- Unique `(checkout_id, purpose)`.
- `(status, expires_at, id)` for the expiry worker.
- `(checkout_id, created_at, id)` for recovery lookup.
- Terminal timestamp/status consistency checks where MySQL can enforce them.

### 10.2 `inventory_reservation_items`

| Column | Rule |
|---|---|
| `id` | `CHAR(26)` canonical ULID primary key |
| `reservation_id` | Owning reservation foreign key |
| `inventory_item_id` | Inventory aggregate foreign key |
| `business_id`, `listing_id` | Immutable scope snapshots |
| `quantity` | Positive reserved quantity |
| reserve balance snapshots | On-hand, reserved, and item version after reserve |
| terminal balance snapshots | Nullable values written by one terminal transition |
| `created_at`, `updated_at` | UTC |

Constraints and indexes:

- Unique `(reservation_id, inventory_item_id)`.
- Unique `(reservation_id, listing_id)`.
- `(inventory_item_id, reservation_id)`.
- Positive quantity check.

### 10.3 `inventory_reservation_history`

Append-only transition records:

- Reservation and checkout references.
- From/to status.
- Stable transition reason.
- Command, correlation, and causation IDs.
- Created timestamp.

Command ID is unique. A separate uniqueness rule deduplicates recovery-required
signals by reservation and failure reason.

### 10.4 Existing support tables

Reuse:

- `inventory_idempotency_records` for reserve, release, and commit responses.
- `inventory_outbox_events` for reservation lifecycle and recovery signals.

The V3 migration makes
`inventory_idempotency_records.result_resource_id` nullable so a stable
business-rule result that creates no reservation, such as insufficient stock,
can be retained without inventing a resource ID. Existing successful records
remain unchanged.

No existing migration is rewritten. The legacy tutorial table remains
quarantined.

## 11. Idempotency Rules

Caller scopes:

```text
ORDER_SERVICE:RESERVE:{checkoutId}:{purpose}
ORDER_SERVICE:RELEASE:{reservationId}
ORDER_SERVICE:COMMIT:{reservationId}
```

Request hashes include every behavior-affecting field:

- Reserve: checkout ID, purpose, exact expiry, and canonical sorted lines.
- Release: reservation ID and reason.
- Commit: reservation ID.

Rules:

- The same scope, key, and hash replays the original HTTP status and response.
- The same scope and key with another hash returns `IDEMPOTENCY_KEY_REUSED`.
- Completed success and stable business-rule results are retained.
- Authentication failures, malformed requests, and transient infrastructure
  failures are not retained.
- Idempotency lookup occurs before state conflict checks.
- Database uniqueness remains the final duplicate-command guard.

## 12. Error Contract

| Status | Code | Meaning |
|---|---|---|
| `403` | `INVENTORY_INTERNAL_AUTH_REQUIRED` | Service token missing or invalid |
| `404` | `INVENTORY_RESERVATION_NOT_FOUND` | Reservation is absent |
| `409` | `INVENTORY_INSUFFICIENT_STOCK` | At least one line lacks available quantity |
| `409` | `INVENTORY_RESERVATION_ALREADY_EXISTS` | Checkout/purpose already has a reservation |
| `409` | `INVENTORY_RESERVATION_EXPIRED` | Commit missed the immutable deadline |
| `409` | `INVENTORY_RESERVATION_NOT_COMMITTABLE` | Released reservation cannot commit |
| `409` | `INVENTORY_RESERVATION_ALREADY_COMMITTED` | Committed reservation cannot release |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Key was reused with another request hash |
| `422` | `INVENTORY_INVALID_RESERVATION` | Invalid IDs, lines, quantity, purpose, or expiry |
| `503` | `INVENTORY_TEMPORARILY_UNAVAILABLE` | Database or required infrastructure unavailable |

Insufficient-stock details include only listing ID, requested quantity, and
current available quantity. They do not expose seller identity or unrelated
balances.

## 13. Security And Service Boundary

- Internal routes remain outside gateway routing.
- Constant-time token comparison is reused from the current availability
  contract.
- Browser authentication cannot call reservation commands.
- Buyer IDs, business IDs, prices, totals, payment state, and order state are
  not accepted.
- Only inventory-service writes reservation and stock balances.
- Logs do not include service tokens, buyer PII, payment data, or complete
  request bodies.
- Reservation IDs and checkout IDs are treated as opaque references.

No seller permission is involved in internal reservation commands. Seller
adjustments continue to use business membership and `INVENTORY_MANAGE`.

## 14. Events And Observability

Transactional outbox types:

- `inventory.reservation.created.v1`
- `inventory.reservation.released.v1`
- `inventory.reservation.expired.v1`
- `inventory.reservation.committed.v1`
- `inventory.reservation.commit-rejected.v1`

Payloads contain:

- Event, reservation, checkout, and command IDs.
- Purpose and terminal status.
- Listing IDs, business IDs, and quantities.
- Resulting inventory versions and safe balance snapshots.
- Expiry and transition timestamps.
- Correlation and causation metadata.

They exclude prices, payment details, service tokens, seller notes, and buyer
PII.

This slice persists outbox records transactionally. Kafka publication and
consumer wiring begin with the first slice that needs a live cross-service
event; direct synchronous command responses remain authoritative here.

Structured logs:

- Reservation success, replay, conflict, and insufficient stock.
- Release, expiry, commit, and terminal replay.
- Commit rejection requiring recovery.
- Expiry batch count and latency.
- Invariant failure and database conflict.

Metrics:

- Active reservation count.
- Reserve attempts by result.
- Insufficient-stock lines.
- Expiry lag and expired count.
- Release and commit counts.
- Idempotency replay/conflict count.
- Recovery-required count.
- Row-lock/deadlock retry count.
- Invariant violation count.

## 15. Test Plan

Domain/unit:

- Request canonicalization and hashing.
- Item limit, distinct listing, quantity, purpose, and expiry validation.
- State transition matrix.
- Commit/release balance calculations.
- Deadline boundary behavior with a fixed clock.
- Replay and different-hash conflict behavior.

MySQL integration:

- Forward migration after V2.
- Multi-line reserve commits all balances atomically.
- One insufficient line rolls back every line.
- Concurrent reserve attempts cannot oversell.
- Opposite request item order does not deadlock the normal path.
- Seller adjustment racing reserve preserves invariants.
- Release and expiry restore availability exactly once.
- Commit reduces on-hand and reserved exactly once.
- Commit preserves available quantity.
- Commit after release/expiry records one recovery signal.
- Header, lines, history, idempotency, and outbox are transactionally
  consistent.
- Database constraints reject impossible balances and duplicate transitions.

Internal API/security:

- Service token is required for every route.
- POST commands require `Idempotency-Key`; GET does not.
- Reserve schema rejects client balances, totals, and business identity.
- Missing inventory is hidden behind the stable reservation error contract.
- Response and standard error envelope match the API contract.
- Reservation routes are not exposed by the gateway.

Worker:

- Only due active reservations are claimed.
- Batches are bounded.
- Concurrent workers do not double-expire.
- A reservation committed while another worker scans is not released.
- Restart and repeated scans are harmless.

Regression:

- CART-02 availability reflects active reservations.
- Seller on-hand adjustment cannot go below reserved.
- Seller inventory reads display current reserved and available values.
- Individual listings remain outside inventory reservation.

## 16. Browser Verification

There is no new buyer page in this infrastructure slice. Browser verification
uses the existing approved seller inventory UI as an observation surface:

1. Sign in as the approved business owner.
2. Open `/seller/inventory` and record a dedicated initialized test item's
   on-hand, reserved, available, and version.
3. Issue a local token-protected reservation command for that item.
4. Reload the seller page and verify reserved increases and available
   decreases without changing on-hand.
5. Release the reservation and verify balances return.
6. Create a short-lived reservation and verify the expiry worker restores
   availability once.
7. Reserve and commit a dedicated quantity; verify both on-hand and reserved
   decrease while available remains stable.
8. Restore the test item's on-hand through the seller adjustment UI with an
   explicit reason, preserving the audit trail.
9. Confirm `/cart` still has validation and repair behavior but no checkout
   action.

Browser verification never edits inventory tables directly. Reservation and
history rows remain as the intended immutable audit record.

### Verification result

The 2026-07-18 local walkthrough verified:

- Reserve changed the dedicated item from `9 / 0 / 9` to `9 / 2 / 7`.
- Explicit release returned the item to `9 / 0 / 9`.
- A short-lived reservation changed the item to `9 / 2 / 7`; the scheduled
  worker then produced `EXPIRED / EXPIRY` and restored `9 / 0 / 9`.
- Commit changed `9 / 1 / 8` to `8 / 0 / 8`.
- Replaying the same commit returned `COMMITTED` without another stock change.
- Reservation history contains one create and one terminal transition for
  each walkthrough reservation.
- `/cart` showed the empty-cart repair path and no checkout action.

The seller inventory page itself could not be used to observe the dedicated
item in the final pass. The local Keycloak and identity fixtures contain
multiple historical subjects for similar seller emails, and a fresh browser
login resolved to a different test identity than the owner of the dedicated
inventory row. The walkthrough did not rewrite account membership or catalog
fixtures to conceal that unrelated local-state problem. Automated seller
inventory regressions and the live authoritative inventory contract both
passed.

## 17. Implementation Order

1. Add the forward-only reservation migration and migration tests.
2. Add reservation records, DTOs, validation, clock, and configuration.
3. Implement canonical idempotency and repository locking operations.
4. Implement atomic reserve plus history and outbox writes.
5. Implement get, explicit release, and idempotent terminal replay.
6. Implement commit and durable recovery-required signaling.
7. Add the bounded expiry worker using `SKIP LOCKED`.
8. Add internal controllers, security/error mapping, and exact API tests.
9. Add concurrency, seller-adjustment race, and invariant integration tests.
10. Run inventory, cart-regression, build, and browser verification.
11. Restore demo balances, update documents, and mark the slice complete.

## 18. Completion Criteria

- `INV-02`, `INV-03`, and `INV-04` are implemented as one cohesive lifecycle.
- Multi-line reserve is all-or-nothing and cannot oversell under concurrency.
- Every reservation has an immutable expiry.
- Explicit release and scheduled expiry restore availability once.
- Commit reduces on-hand and reserved once without changing available.
- Commit after release or expiry fails safely and records one durable recovery
  signal.
- Every POST command is idempotent and different-payload key reuse conflicts.
- Reservation state, lines, history, balances, idempotency, and outbox remain
  transactionally consistent.
- Internal service authorization and gateway non-exposure are tested.
- Existing seller adjustment and CART-02 behavior remain correct.
- Browser verification passes without introducing a checkout dead end.

## 19. Deferred Work

- V2-IAM-01 owns the buyer address book.
- V2-CHK-01 owns checkout snapshots, totals, lifetime selection, and reserve
  orchestration.
- V2-PAY-01 owns provider intent and verified webhook state.
- V2-ORD-01 owns payment-success commit orchestration, recovery reservation,
  order confirmation, and operations items.
- V2-ORD-03 owns cancellation/refund inventory compensation.
- Reservation extension, partial release, partial commit, backorders,
  warehouses, and location-level stock require separate approved requirements.

## 20. Implementation Result

Implemented:

- Forward-only Flyway migration `V3__create_inventory_reservation_lifecycle.sql`.
- Checkout-scoped reservation headers, immutable lines, transition history,
  idempotency records, and transactional outbox events.
- Service-token-protected reserve, read, release, and commit endpoints.
- Deterministic multi-line row locking and all-or-nothing stock reservation.
- Exactly-once release, expiry, and commit balance transitions.
- Bounded scheduled expiry using `FOR UPDATE SKIP LOCKED`.
- Durable, deduplicated commit-rejected recovery signals.
- Structured lifecycle logs and Micrometer counters.

Verification:

- Inventory-service MySQL Testcontainers suite: 17 passed.
- CART-01/CART-02 regression suites: 18 passed.
- Inventory compile and package: passed.
- Live reserve, release, expiry-worker, commit, and commit-replay walkthrough:
  passed.
- Browser cart boundary: passed.

Deferred dependencies remain V2-CHK-01 checkout orchestration, V2-PAY-01
payment confirmation, V2-ORD-01 recovery orchestration, and live Kafka outbox
publication when the first consumer slice requires it.
