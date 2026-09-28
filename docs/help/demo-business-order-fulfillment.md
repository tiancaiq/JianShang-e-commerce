---
article_id: HELP-SELL-ORDERS-001
title: Fulfill local demo business orders
audience: approved business staff in the checkout demo
availability: demo-checkout only
---

# Fulfill local demo business orders

## Common questions

- Where do business sellers see paid orders?
- How do I accept, process, and ship an order group?
- How do I handle a buyer return?

## Who this applies to

Approved business staff with permission for the business that owns an order group.

## Prerequisites

- The seller portal must show **Orders**.
- The business order and fulfillment capabilities must be enabled in the matching gateway and order-service runtime.

## Pages

- [Business order queue](/seller/orders)
- [Business order detail](/seller/orders/{businessOrderId})

## Fulfill a demo order group

1. Open **Orders** in **MSB Seller**.
2. Use **Status** and **Apply** to filter the queue, then select **View**.
3. Review the items, totals, shipping address, status history, and cancellation state.
4. Select **Accept order group** and confirm the action.
5. Select **Mark processing** and confirm.
6. Enter **Carrier**, **Service**, and **Tracking number**, then create the shipment and confirm.
7. When appropriate for the local demo, select **Simulate local-demo delivery** and confirm.

## Handle a demo return

1. Open the affected order detail after the buyer requests a return.
2. Under **Post-delivery return**, select **Authorize return**.
3. When the demo return arrives, choose **Inventory disposition**: **Restock as sellable** or **Do not restock**.
4. Select **Mark return received**.
5. Follow the return and demo refund status on the order.

## Availability and safety limits

- This workflow is for local demonstration only. Tracking and delivery are manually entered or simulated.
- The current workflow does not buy postage or call a real carrier.
- Demo refunds do not move real money.
- A business user can act only within a business membership and permission checked by the backend.
- A buyer cancellation can stop all order groups before seller acceptance. Review the cancellation state before acting.
