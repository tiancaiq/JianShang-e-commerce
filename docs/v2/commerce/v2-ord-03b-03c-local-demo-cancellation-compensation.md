# V2-ORD-03B/03C: local-demo cancellation compensation

## Scope

This bounded runtime slice completes the existing V2-ORD-03A whole-order request. ORD-03B refund orchestration and ORD-03C inventory compensation are delivered together because a final cancellation must durably recover both committed resources. Returns, partial cancellation, notifications, payouts, carrier interception, and real refunds remain deferred.

## Reused authority

- The ORD-03A buyer-owned `POST /api/v1/orders/{orderId}/cancellation-requests` command remains the only public cancellation mutation.
- Its `If-Match`, seven-day `Idempotency-Key`, immutable policy snapshot, request/group evidence, history, and transactional outbox remain authoritative.
- Orders remain whole-order aggregates. A business seller cannot decide another group's state and no group-scoped cancellation command is exposed.
- Payment Service owns the refund record and derives amount/currency from its succeeded intent.
- Inventory Service owns restoration and derives quantities from the committed checkout reservation.

## Authoritative policy

`LOCAL_DEMO_CANCELLATION_V1` snapshots `BEFORE_FULFILLMENT`. The request is accepted only when every group is still `PENDING_ACCEPTANCE`, has cancellation state `NONE`, and its immutable cutoff is in the future.

Order confirmation snapshots `2037-01-01T00:00:00Z` as the bounded local-demo cutoff only for this policy version. The operative product boundary remains seller acceptance; the fixed horizon prevents an open-ended nullable cutoff while keeping legacy `LOCAL_DEMO_V1` orders non-cancellable.

V10 repairs only cancellation-policy orders that were confirmed and still pending acceptance during the activation gap. It does not backfill accepted, processing, shipped, delivered, cancelled, or legacy-policy groups.

| Fulfillment state | Result |
| --- | --- |
| `PENDING_ACCEPTANCE` | Eligible when policy and cutoff also allow it |
| `ACCEPTED` | Rejected; the current approved contract does not support post-acceptance cancellation |
| `PROCESSING` | Rejected |
| `SHIPPED` | Rejected; returns are deferred |
| `DELIVERED` | Rejected; returns are deferred |

The request and fulfillment commands lock the same business-order rows. If cancellation locks first, `CANCELLATION_PENDING` blocks seller fulfillment. If acceptance/processing/shipping locks first, the changed fulfillment state blocks cancellation. The model therefore cannot produce both a final cancellation and a valid later shipment.

## State and evidence

```text
CONFIRMED / PENDING_ACCEPTANCE / NONE
  -> CANCELLATION_REQUESTED / CANCELLATION_PENDING / request PENDING
  -> CANCELLED / CANCELLED / request AUTO_APPROVED
  -> CANCELLED / request COMPLETED / inventory SUCCEEDED / refund SUCCEEDED
```

The automatic decision writes order and group histories, `order.cancellation_auto_approved`, and one durable `order_cancellation_compensations` row in its transaction. The order is intentionally allowed to remain `CANCELLED` with refund `PENDING` while a dependency is unavailable; it is never resurrected.

## Inventory recovery

Order Service calls the internal idempotent reservation-restock command with a stable request-derived key. Inventory locks the committed reservation and all referenced inventory items, restores each exact committed quantity, writes one `ORDER_CANCELLATION_RESTOCK` movement per line, one recovery record, and one outbox event. Unique request, order, reservation, and command keys prevent a retry from increasing stock twice. Unrelated inventory rows are not selected or changed.

## Fake refund

Order Service calls Payment's internal refund command with only the payment intent, order, and cancellation request identifiers. The client cannot provide amount or currency. Payment locks the succeeded intent, derives the full immutable amount/currency, and calls `FAKE_LOCAL_DEMO_V1`, which returns a deterministic reference. Payment persists exactly one succeeded refund, attempt, status history, and `payment.refunded` outbox event.

The browser displays **Local demo refund** and **No real money is moved.** Provider references, webhook data, outbox metadata, and internal authorization state are not included in the buyer projection. Existing payment webhook HMAC verification is unchanged; the bounded refund does not use a provider callback.

The seller queue and detail preserve the stored fulfillment status as history,
but derive the primary operational label from cancellation state. A finally
cancelled group is displayed as **Cancelled**, is excluded from fulfillment
status filters such as **Pending acceptance**, and is available through the
server-side **Cancelled** filter.

## Recovery

`order_cancellation_compensations` is the durable retry source. Inventory and refund progress are stored independently. The worker retries pending work with stable downstream idempotency keys after restart or a transient failure. Completion occurs only after both downstream services report success. Duplicate commands replay the existing downstream record.

## Runtime gates

All new capabilities default off. The authoritative `docker-compose.demo.yml` plus `docker-compose.cart-runtime.yml` overlay explicitly enables:

- `ORDER_CANCELLATION_REQUESTS_ENABLED=true`
- `ORDER_CANCELLATION_PROCESSING_ENABLED=true`
- `INVENTORY_CANCELLATION_RESTOCK_ENABLED=true`
- `PAYMENT_REFUNDS_ENABLED=true`
- `GATEWAY_FEATURE_ORDER_CANCELLATION=true`
- `CHECKOUT_POLICY_VERSION=LOCAL_DEMO_CANCELLATION_V1`

It explicitly keeps notifications and real external payment/refund providers disabled. Run `scripts/verify-cart-runtime.ps1` after any recreation; it exits nonzero when the gateway or a commerce service has silently drifted to the base-only configuration.
