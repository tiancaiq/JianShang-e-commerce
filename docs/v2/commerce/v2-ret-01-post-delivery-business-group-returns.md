# V2-RET-01 Post-Delivery Business-Group Returns

Status: implemented and verified with MySQL integration, focused regression,
local-container preflight, and browser walkthrough evidence on 2026-08-11.

## Boundary and policy

Returns are an Order-owned aggregate separate from cancellation and never change
the fulfillment state stored on `business_orders`. `LOCAL_DEMO_RETURN_POLICY_V1`
allows one whole-group return within 30 days of the authoritative shipment
`delivered_at`. The group must be `DELIVERED`, paid, uncancelled, buyer-owned,
and without an existing return. Partial quantities, exchanges, rejection,
returnless refunds, real labels, and real carriers remain disabled.

The lifecycle is `RETURN_REQUESTED -> RETURN_AUTHORIZED -> RETURN_IN_TRANSIT ->
RETURN_RECEIVED -> RETURN_COMPLETED`. Refund state is independently truthful as
`NONE -> PENDING -> PROCESSING -> SUCCEEDED`. Authorization creates a stable
`Demo Returns` reference `RETURN-{returnId}` and states that no real shipment exists.

## Money, inventory, reliability, and authorization

Receipt requires `RESTOCK_SELLABLE` or `DO_NOT_RESTOCK`. Sellable disposition
calls Inventory Service with the checkout reservation plus exact business scope;
only that group's purchased quantities move once with reason
`CUSTOMER_RETURN_RESTOCK`. Non-restock records the decision without changing stock.

Payment Service reuses its deterministic fake provider. Order Service derives
the amount from immutable `business_orders.subtotal`; shipping and tax are zero
for the current demo and no real money moves. The browser never supplies money,
payment intent, or provider data.

Commands require authentication, `If-Match`, and `Idempotency-Key`. SQL
uniqueness, conditional versions, immutable history, transactional outbox, and
the retryable post-receipt worker make dependency failures and replay safe.
Buyer ownership and `ORDER_FULFILL` business membership are server-authoritative.
One returned group never changes a sibling group.

Committed events `return.requested`, `return.authorized`, `return.received`, and
`return.refund_completed` create `SELLER_RETURN_REQUESTED`,
`BUYER_RETURN_AUTHORIZED`, `BUYER_RETURN_RECEIVED`, and
`BUYER_RETURN_REFUND_COMPLETED` through the existing notification adapter.
