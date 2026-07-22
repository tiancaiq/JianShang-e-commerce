# V2-ORD-03A Cancellation Request And Eligibility Foundation

Status: source implemented on 2026-07-20; disposable MySQL verification is
environment-blocked while Docker is stopped. The business counter remains
`0/3` until that mandatory gate is green.

Requirements: `ORD-04 Cancel eligible order`.

## 1. Scope

This slice adds a buyer-only, whole-order cancellation request foundation to
Order Service. It records an eligible request and moves the Order-owned state
to pending cancellation. It does not decide the request or perform a refund,
inventory compensation, shipment action, notification, or provider call.

Shipping and tracking remain parked. The implementation does not call, reuse,
or depend on the parked fulfillment command.

## 2. Default-Off Boundary

The capability is controlled by:

```properties
order.cancellation-requests.enabled=false
```

When disabled, the command returns `404
ORDER_CANCELLATION_NOT_AVAILABLE` before validating the path, body, headers,
actor, buyer identity, repository, history, or outbox.

No gateway, frontend, browser, Compose, or runtime route is activated by this
slice.

## 3. Buyer API

```http
POST /api/v1/orders/{orderId}/cancellation-requests
If-Match: "0"
Idempotency-Key: cancel-key-001
```

The request has no body. A positive `Content-Length` or any
`Transfer-Encoding` is rejected.

`orderId` is a canonical ULID. `If-Match` accepts one plain or quoted
nonnegative long. Weak validators, wildcards, lists, missing values, malformed
values, and overflow are rejected. `Idempotency-Key` must match
`[A-Za-z0-9._:-]{8,128}`.

Success returns `200`, `Cache-Control: no-store`, a quoted ETag containing the
new order version, and only:

```json
{
  "orderId": "01...",
  "cancellationRequestId": "01...",
  "status": "CANCELLATION_REQUESTED",
  "requestStatus": "PENDING",
  "version": 1,
  "requestedAt": "2026-07-20T02:00:00Z"
}
```

Buyer order detail now includes the persisted order version and returns the
same value as its ETag. Buyer order list remains unchanged and omits version.

## 4. Validation Order And Errors

The deterministic command order is:

1. feature gate;
2. canonical order ID;
3. no-body boundary;
4. `If-Match`;
5. `Idempotency-Key`;
6. actor and buyer identity;
7. durable idempotency replay or conflict;
8. buyer-owned order lock;
9. version and order state;
10. all business-group state, structured policy, item-snapshot, and cutoff
    evidence.

Stable errors are:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `ORDER_ID_INVALID` | The path is not a canonical order ULID. |
| 400 | `ORDER_CANCELLATION_BODY_NOT_ALLOWED` | The command contains a body. |
| 400 | `ORDER_VERSION_REQUIRED` | `If-Match` is missing or invalid. |
| 400 | `ORDER_CANCELLATION_IDEMPOTENCY_KEY_REQUIRED` | The key is missing or invalid. |
| 404 | `ORDER_CANCELLATION_NOT_AVAILABLE` | The feature is disabled. |
| 404 | `ORDER_NOT_FOUND` | The order is missing or owned by another buyer. |
| 409 | `ORDER_VERSION_CONFLICT` | The stored order version differs. |
| 409 | `ORDER_CANCELLATION_STATE_CONFLICT` | Order/group state is not eligible. |
| 409 | `ORDER_CANCELLATION_NOT_ALLOWED` | A structured snapshot policy disallows cancellation. |
| 409 | `ORDER_CANCELLATION_WINDOW_CLOSED` | A group cutoff is absent or reached. |
| 409 | `ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT` | The key has a different canonical hash. |
| 409 | `ORDER_CANCELLATION_IN_PROGRESS` | An incomplete durable command is observed. |
| 503 | `ORDER_CANCELLATION_DEPENDENCY_UNAVAILABLE` | Buyer identity resolution failed. |
| 503 | `ORDER_CANCELLATION_UNAVAILABLE` | Transactional persistence failed. |

Missing and cross-buyer orders use the same repository predicate, response,
and metric result.

## 5. Structured Policy And Eligibility

V6 adds `paid_order_cancellation_mode` to both
`platform_policy_versions` and `checkout_policy_snapshots`.

Allowed values are:

- `NOT_ALLOWED`
- `BEFORE_FULFILLMENT`

All existing policy versions and snapshots backfill to `NOT_ALLOWED`.
`LOCAL_DEMO_V1` remains `NOT_ALLOWED`, and checkout continues selecting it.
This slice never parses cancellation policy text.

V6 adds one immutable future/test version,
`LOCAL_DEMO_CANCELLATION_TEST_V1`, with `BEFORE_FULFILLMENT`. It is not the
selected checkout policy and does not activate cancellation for current
orders.

Every business group is eligible only when:

- the buyer order is `CONFIRMED` with payment status `SUCCEEDED`;
- its cancellation status is `NONE`;
- its checkout policy snapshot mode is `BEFORE_FULFILLMENT`;
- all persisted order items in the group use that snapshot version;
- at least one item exists; and
- `cancellation_cutoff_at` is present and strictly after the command time.

The cutoff is the future-safe pre-fulfillment predicate. Cancellation does not
query or depend on an unimplemented shipment state.

## 6. Atomic State Transition

The authoritative optimistic version is `orders.version`. The command locks
the buyer-owned order and every business group in deterministic ID order.

One transaction performs:

- `orders`: `CONFIRMED` to `CANCELLATION_REQUESTED`, version incremented once;
- every `business_orders` row: cancellation `NONE` to
  `CANCELLATION_PENDING`;
- one `PENDING` cancellation request;
- immutable policy/cutoff/state evidence for every group;
- one immutable group cancellation history row per group;
- one immutable order history row;
- one version-1 `order.cancellation_requested` outbox row; and
- the completed durable idempotency response.

The buyer aggregate status does not imply refund, inventory release, shipment
change, or cancellation acceptance.

## 7. Idempotency And Concurrency

The operation is `REQUEST_ORDER_CANCELLATION`. The durable key tuple is
`(buyerId, operation, Idempotency-Key)`. The canonical SHA-256 input is:

```text
REQUEST_ORDER_CANCELLATION
{orderId}
{expectedOrderVersion}
```

Records expire after exactly `P7D`; lazy purge is bounded to 100 rows. The
command uses database uniqueness and row locks, not a process-local lock.

- A command is expired when `expires_at <= request time`. The exact matching
  expired row is removed before replay lookup, so the key establishes a fresh
  command and cannot leak its stale response or state.
- Same tuple and hash returns the original body and ETag.
- Same tuple with a different hash returns the idempotency conflict.
- Concurrent same-key commands execute once and replay.
- Concurrent use of the same buyer/key across different orders has different
  canonical hashes: one command wins and the other returns
  `ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT`, never a generic availability
  error.
- Concurrent distinct keys at the same version produce one success and one
  version conflict.
- A distinct key using the current version after a pending request returns the
  existing request and adds no history or outbox event.

## 8. Persistence And Migration Lineage

Forward migration:

`V6__create_order_cancellation_request_foundation.sql`

V6 adds:

- structured policy-mode columns and checks;
- `orders.version`;
- `business_orders.cancellation_cutoff_at`;
- expanded order and cancellation-status checks;
- `order_cancellation_requests`;
- `order_cancellation_request_groups`;
- `order_cancellation_commands`; and
- `business_order_cancellation_history`.

The release lineage is exactly `V1-V4 -> V6`. V5 and all parked SHP work are
permanently excluded from this release and must not be applied, published, or
asserted by the ORD-03A harness. V1 through V4 and V6 remain unchanged by this
P1 correction. Future shipping work requires a newly designed migration with
a version greater than V6.

## 9. Event And Privacy

The transactional outbox row uses:

- aggregate type `ORDER`;
- aggregate ID and partition identity `orderId`;
- event type `order.cancellation_requested`;
- event version `1`; and
- causation ID equal to the durable command ID.

The payload contains only event identity/time, order ID, cancellation request
ID, pending statuses, order version, and business-order IDs. It excludes buyer
identity, actor identity, address, item details, payment/provider metadata,
idempotency keys, and request hashes.

Logs use correlation ID, low-cardinality results, and a short hash of order
identity. Metrics expose only bounded result tags.

## 10. Verification Gate

Source-complete tests cover:

- authentication and exact HTTP headers/body/ETag/response fields;
- disabled zero-work behavior and validation precedence;
- Spring production-constructor wiring;
- persisted buyer-detail version/ETag;
- structured policy and cutoff eligibility;
- buyer isolation;
- durable replay, hash conflict, semantic replay;
- same-key and distinct-key concurrency;
- multi-group state/history/outbox uniqueness;
- transaction rollback; and
- outbox privacy.

The disposable MySQL harness migrates only `V1-V4 + V6`, verifies the policy
backfill/future mode and schema, then runs the transactional scenarios. It
also covers expired-key replacement and concurrent cross-order key conflict.

Docker was stopped during the 2026-07-20 implementation run, so this mandatory
gate remains unexecuted. The slice must not advance to `1/3` until the harness
is green. No `V2-ORD-03B` work may begin from this state.

The shared domain/API/database/roadmap files already contain concurrent
user-owned edits. This isolated document is the authoritative ORD-03A
addendum; folding its exact contract into those shared files is deferred to a
separately coordinated documentation pass.
