---
article_id: HELP-CHECKOUT-001
title: Use saved addresses and local demo checkout
audience: signed-in buyers in the checkout demo
availability: demo-checkout only
---

# Use saved addresses and local demo checkout

## Common questions

- How do I add a delivery address?
- How do I start checkout?
- Will the demo payment charge real money?
- Why is **Continue to checkout** unavailable?

## Who this applies to

Signed-in buyers using an environment that shows **Addresses**, **Continue to checkout**, and **Your orders**.

## Prerequisites

- The `demo-checkout` frontend and matching commerce services and gateway features must be enabled.
- Your cart must contain eligible business items and pass its current price and stock checks.
- At least one saved address is required.

## Pages

- [Saved addresses](/account/addresses)
- [Cart](/cart)
- [Checkout review](/checkout)
- [Checkout payment step](/checkout/{checkoutId})

## Save a delivery address

1. Open **Account**, then **Addresses**.
2. Select **Add address**.
3. Complete **Recipient name**, **Phone**, **Address line 1**, **City**, **State or region**, **Postal code**, and the two-letter **Country code**. **Label** and **Address line 2** are optional.
4. Select **Save address**.
5. Use **Set default**, **Edit**, or **Delete** on a saved address when needed.

You can save up to 20 addresses.

## Complete local demo checkout

1. Open **Your cart** and repair any price, stock, store, or currency issues.
2. When the cart says **Ready for checkout**, select **Continue to checkout**.
3. On **Review checkout**, choose a **Delivery address**.
4. Review **Items**, **Subtotal**, **Shipping**, and **Tax**.
5. Select **Start checkout**. The application creates a short-lived checkout and reserves eligible demo inventory.
6. On **Review and pay**, verify the item groups, totals, and **Delivery address snapshot**.
7. Select **Complete demo payment**.
8. Wait while the page confirms the demo order. When successful, it opens the order detail page.

## Availability and safety limits

- This is a local demonstration. **Complete demo payment** never charges real money.
- Demo tax is zero and demo shipping is free in the committed checkout runtime. They are calculation adapters, not real carrier or tax quotes.
- Checkout sessions are time-limited and can fail or expire if inventory or services change.
- The saved address is copied into the order as a purchase-time snapshot.
- Do not use this workflow as evidence of a real payment, shipment, refund, or financial obligation.
