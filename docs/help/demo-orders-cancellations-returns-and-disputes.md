---
article_id: HELP-ORDERS-001
title: View local demo orders, cancellations, returns, and disputes
audience: signed-in buyers in the checkout demo
availability: demo-checkout only
---

# View local demo orders, cancellations, returns, and disputes

## Common questions

- Where can I see my business-store orders?
- Can I cancel an order?
- How do I request a return?
- How do I report a problem with one store group?
- Does a dispute automatically create a refund?

## Who this applies to

Buyers who completed the local demo checkout for business listings. Individual trades never appear in **Your orders**.

## Prerequisites

- The current environment must show **Your orders**.
- Cancellation, returns, and notifications are separate capabilities and may not be enabled in every commerce demo.

## Pages

- [Order history](/account/orders)
- [Order detail](/account/orders/{orderId})
- [New store-group dispute](/account/orders/{orderId}/disputes/new?businessGroupId={businessOrderId})
- [Dispute detail](/account/disputes/{disputeId})

## View an order

1. Open **Account**, then **Your orders**.
2. Select **View order**.
3. Review each store group, item quantity, group total, order status, payment status, delivery address snapshot, and timeline.
4. If the seller created a demo shipment, review its carrier, service, tracking number, and demo delivery state.

## Cancel an eligible order

1. Open the order while every store group is still awaiting seller acceptance.
2. Under **Whole-order cancellation**, select **Cancel entire order**.
3. Read the confirmation. Cancellation affects the entire order, not one store group.
4. Select **Cancel entire order** again to confirm, or **Keep order** to leave it unchanged.
5. Refresh the order to follow inventory restoration and the local demo refund state.

## Request an eligible return

1. Open a delivered order.
2. In the relevant store group, find **Post-delivery return**.
3. Choose a **Reason**: **No longer needed**, **Item not as expected**, **Damaged item**, **Wrong item**, or **Other**.
4. Add an optional **Comment**.
5. Select **Return items**. The request covers the entire store group.
6. Follow the return status, demo return shipment, and demo refund state on the order.

## Report a problem with a store group

1. On an eligible store group, select **Report a problem with this store group**.
2. Choose a **Reason** and describe **What happened?**
3. Select **Submit dispute**.
4. On the dispute page, use **Add information** and **Respond** while the dispute is open.
5. Review the timeline and any recorded resolution.

## Availability and safety limits

- Cancellation is available only before any seller accepts a store group and only when the cancellation capability is enabled.
- Returns are available only for eligible delivered store groups in the enabled demo. The committed demo runtime uses a 30-day return window.
- Current cancellation and return refunds are fake local-demo records. No real money is moved.
- Submitting a dispute does not create a refund or enforcement action. A displayed refund recommendation is a recommendation only.
- Orders, cancellations, returns, and disputes apply to business purchases. They do not apply to individual seller trades.
