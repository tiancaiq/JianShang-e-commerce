# V2-SHP-01A Accept Paid Business Fulfillment Group

Status: PM-approved authoritative contract; implementation verification pending.

Requirement: `SHP-01 Accept order for fulfillment`.

Depends on:

- `V2-ORD-01A` paid order confirmation and immutable business groups;
- `V2-ORD-02B` business-scoped fulfillment reads; and
- Auth-owned active membership and permission expansion.

## 1. Scope

This slice adds only the default-disabled command:

```text
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/accept
```

It accepts no request body. It changes one paid, SQL-owned business fulfillment
group from `PENDING_ACCEPTANCE` to `ACCEPTED`. It does not change the buyer
order status, create a shipment, call Payment or Inventory, publish to a
broker, notify a user, or expose a gateway/frontend route.

## 2. Feature And Authorization Boundary

Order Service property `business-orders.acceptance-enabled` is `false` by
default. Disabled requests return `404
BUSINESS_ORDER_ACCEPTANCE_NOT_AVAILABLE` before actor resolution, Auth calls,
Order persistence, or outbox work.

Enabled requests require an authenticated actor with active membership in the
path business and `ORDER_FULFILL`. Auth maps the current `OWNER` role to this
permission and does not map it to `MANAGER`. Missing membership, missing
permission, a missing group, and cross-business access all return the same
`404 BUSINESS_ORDER_NOT_FOUND`. Auth throttling or outage returns `503
BUSINESS_ORDER_ACCEPTANCE_DEPENDENCY_UNAVAILABLE`.

## 3. Wire Contract

Required headers:

```text
If-Match: 0
Idempotency-Key: seller-accept-0001
```

`If-Match` accepts a plain or quoted nonnegative long. Missing or malformed
values return `400 BUSINESS_ORDER_VERSION_REQUIRED`.

`Idempotency-Key` must match `[A-Za-z0-9._:-]{8,128}`. Missing or malformed
values return `400 BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED`. Both path IDs are
canonical uppercase ULIDs; malformed IDs return `400 BUSINESS_ORDER_ID_INVALID`.

Success is `200` with an `ETag` containing the quoted new version and exactly:

```json
{
  "businessOrderId": "01K...",
  "fulfillmentStatus": "ACCEPTED",
  "version": 1,
  "updatedAt": "2026-07-20T01:00:00Z"
}
```

The response contains no buyer, parent-order, payment/provider, item, address,
actor, idempotency, history, or outbox data.

## 4. Preconditions And Concurrency

The command locks and predicates by both `businessId` and `businessOrderId`.
A new transition requires:

- matching stored group version;
- `fulfillment_status=PENDING_ACCEPTANCE`;
- `cancellation_status=NONE`; and
- parent `orders.payment_status=SUCCEEDED`.

A stale version returns `409 BUSINESS_ORDER_VERSION_CONFLICT`. With the correct
version, a non-pending or cancellation state returns `409
BUSINESS_ORDER_STATE_CONFLICT`; an unpaid parent returns `409
BUSINESS_ORDER_NOT_PAID`.

The update is one SQL conditional update by tenant, group, version, pending
state, and cancellation state. It increments the group version exactly once.
There is no process-local lock. Distinct keys using the same expected version
produce one success and one bounded version/state conflict.

## 5. Idempotency And Retention

Order Flyway V5 owns a durable acceptance-command table unique by:

```text
(actorUserId, businessId, operation, idempotencyKey)
```

The operation is fixed to `ACCEPT_BUSINESS_ORDER`. Canonical SHA-256 covers the
operation, business ID, business-order ID, and expected version. The same tuple
and hash returns the original response and ETag. Reusing the tuple with a
different hash returns `409 BUSINESS_ORDER_IDEMPOTENCY_CONFLICT` before a
second transition.

Records are retained for `P7D`. A bounded lazy purge uses the expiry index.
Same-key concurrency is serialized by the database unique constraint and row
lock, executes once, and replays after the first transaction commits.

## 6. Durable Records

V5 adds `business_orders.version`, allows only `PENDING_ACCEPTANCE` or
`ACCEPTED` in the current fulfillment check, and adds append-only
`business_order_status_history`. The acceptance row records the group and
business IDs, old/new states, new version, `BUSINESS_ACCEPTED`, internal actor
user ID, correlation ID, durable command ID as causation, and creation time.

The same transaction inserts one existing `order_outbox_events` row:

- aggregate type: `BUSINESS_ORDER`;
- aggregate/partition ID: business-order ID;
- event type/version: `business_order.accepted` / `1`;
- causation ID: durable command ID; and
- propagated bounded correlation ID.

The payload contains only event identity/version/time, business-order ID,
parent order ID, business ID, `ACCEPTED`, and the new business-order version.
Replay inserts no additional history or outbox row. No dispatcher or broker is
introduced.

## 7. Atomicity, Safety, And Verification

The conditional update, immutable history, outbox event, and completed
idempotency result commit in one Order Service transaction. Failure rolls back
all four.

Metrics use only bounded outcomes: accepted, replayed, version conflict, state
conflict, not paid, and dependency failure. Logs contain correlation, bounded
result, and hashed identifiers only.

Required verification covers default-off zero work; exact headers, response,
and ETag; owner/manager permissions; tenant hiding; paid/state/version checks;
replay/hash conflict; same-key and distinct-key concurrency; rollback; exactly
one history/outbox row; V1-V4 immutability; V5 MySQL migration; safe
observability; and full Auth/Order packages.

## 8. Deferred Work

`V2-SHP-01B` owns partial shipment creation. `V2-SHP-01C` owns shipment state,
buyer shipment snapshots, and aggregate status derivation. Gateway, frontend,
browser/runtime activation, notification, cancellation, refund, payment,
inventory, and provider behavior remain deferred.
