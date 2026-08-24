# ADM-ORD-01/02 — Admin Order Operations

Status: implemented; verification evidence is recorded in the delivery report.

## Boundary and ownership

Order Service owns the administrative order aggregate view and commands. It
reads its authoritative `orders`, `business_orders`, immutable `order_items`,
checkout policy/address snapshots, fulfillment history, cancellation workflow,
and return references. No admin copy of commerce data exists.

Auth Service remains authoritative for effective admin permissions, safe
identity labels, and active buyer/business enforcement context. Product Service
remains authoritative for current listing state and listing enforcement. Payment Service remains authoritative for the
payment intent. Inventory Service remains authoritative for the reservation.
Order detail uses the existing authenticated service boundaries and explicitly
labels a last-known Order projection when a live dependency is unavailable.

The current order model contains business purchases only. A single buyer order
may contain multiple business fulfillment groups; there is no individual
seller order or `sellerUserId` filter in this slice.

## Permissions

- `admin.order.read`: search and detail.
- `admin.order.cancel`: cancellation dry run and execution.
- `admin.order.manage`: reserved for later bounded order commands; it does not
  enable arbitrary status editing.
- `admin.user.pii.read`: separately controls complete order-time address data.

Super/platform admins receive all three order permissions. Support admins and
auditors receive read only. Backend checks the effective Auth-owned permission
set on every request; Angular guards and capability checks are presentation
safeguards only.

## APIs

```text
GET  /api/v1/admin/orders
GET  /api/v1/admin/orders/{orderId}
POST /api/v1/admin/orders/{orderId}/cancel/dry-run
POST /api/v1/admin/orders/{orderId}/cancel
```

Search is SQL-side and bounded by `page`/`size`. Supported filters are `q`,
`buyerUserId`, `businessId`, `listingId`, `status`, `paymentStatus`,
`fulfillmentStatus`, `createdFrom`, and `createdTo`. Sort is allowlisted and
always has `orderId` as its deterministic tie-breaker.

`fulfillmentStatus` matches the cancellation-aware aggregate value returned in
each queue row, not an arbitrary historical seller-group fulfillment value.
Cancellation takes precedence, followed by cancellation-pending and then the
least-advanced fulfillment group. Therefore `PENDING_ACCEPTANCE`, `ACCEPTED`,
`PROCESSING`, `SHIPPED`, and `DELIVERED` filters exclude rows whose displayed
aggregate fulfillment value is `CANCELLED` or `CANCELLATION_PENDING`.

Queue results contain IDs, safe labels, lifecycle states, totals, item count,
timestamps, and version. They do not contain addresses, phone numbers, raw
payment details, or provider secrets.

## Detail and historical truth

The detail response distinguishes `titleAtPurchase`, `skuAtPurchase`, price,
quantity, condition, image reference, catalog version, business/store IDs, and
policy version from a separately labelled current Product listing. Deleted,
suspended, or edited current listings do not erase or replace purchase-time
snapshots.

Pricing, checkout, masked shipping address, fulfillment groups, live-or-stale
inventory context, safe payment context, cancellation/refund orchestration,
active buyer/business/listing enforcement links, normalized history, and backend-computed
capabilities are returned together. Address recipient, phone, and street fields
are present only with `admin.user.pii.read`.

Payment detail contains only the intent ID, status, provider name, safe provider
reference, authorized/captured/refunded amounts, currency, and reconciliation
source. Card data, credentials, webhook secrets, and raw provider payloads are
never returned. Inventory detail contains only reservation identity/status,
version, expiry, quantities, and release state.

## Timeline

Order Service normalizes persisted order, fulfillment, shipment, cancellation,
existing return, and admin-command events. It does not infer events from a
current status. Admin cancellation records the real Auth-derived admin actor,
reason code/text, request ID, and correlation ID in append-only
`order_admin_events`. Existing Order-owned histories and outbox events remain
the lifecycle source.

## Safe administrative cancellation

There is no generic status patch or status dropdown. Dry run requires the
current order version and persists nothing. It performs no refund, reservation
mutation, order transition, history insert, or success audit.

Execution is allowed only when all of the following remain true under locks:

- the order is `CONFIRMED` with `SUCCEEDED` payment;
- every business group is `PENDING_ACCEPTANCE` with no cancellation state;
- every immutable policy snapshot is `BEFORE_FULFILLMENT` and its cutoff is
  still open;
- the existing cancellation-request and compensation workers are enabled; and
- `expectedOrderVersion` still matches.

An accepted admin command writes the same `CANCELLATION_REQUESTED` aggregate,
group evidence, histories, and outbox contract consumed by the existing
auto-decision and compensation worker. That worker performs the already
implemented idempotent inventory restock and payment refund before completing
the order as `CANCELLED`. Admin execution never creates a second refund or
inventory implementation.

If compensation is disabled, payment is unsupported, fulfillment has begun, a
policy/window blocks cancellation, or the order changed, the command fails
before mutation. Captured orders are never partially cancelled without the
refund path.

## Concurrency and retries

Admin cancellation commands are keyed by `(actorAdminId, idempotencyKey)` and
store a canonical request hash. Identical completed retries replay the result;
changed payloads return `409 ORDER_IDEMPOTENCY_CONFLICT`; in-progress retries
return a stable conflict. Order and fulfillment-group locks plus the exact
version predicate permit one domain transition. Angular invalidates a dry run
and reloads on `409`; it never silently retries.

## Enforcement and navigation

Enforcement controls future marketplace capability, not access to historical
obligations. Orders remain visible when a buyer, business, or listing is
restricted. Order detail links to the existing user, business, and listing
admin surfaces instead of duplicating their controls.

Order-linked dispute events now appear in the normalized administrative order
timeline. Dispute investigation and remedies remain owned by the separate
`ADM-DSP-00/01/02` aggregate and do not alter ADM-ORD cancellation behavior.

## Deferred

`ADM-DSP-00/01/02` owns dispute/return initiation and resolution tooling.
`ADM-FIN-00/01/02` owns general payment search and controlled refunds.

ADM-FIN is now implemented. Its cumulative Payment-owned ceiling includes cancellation, business-return, and admin refunds. Consequently, the legacy all-or-nothing cancellation refund is rejected if another refund source has already committed value; cancellation never tops up past captured funds. See [admin-financial-operations.md](admin-financial-operations.md).

ADM-SUP-00/01 is also implemented as a separate coordination layer. Support
can validate and link an existing order and navigate here, but it cannot invoke
cancellation or edit order state. See
[admin-support-operations.md](admin-support-operations.md).
Payout administration waits for a payout architecture. Support, catalog,
system operations, governance, analytics, and all AI administration remain
later milestones. `admin.order.manage` is reserved until one of those bounded
commands is approved.
