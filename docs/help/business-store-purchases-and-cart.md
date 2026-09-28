---
article_id: HELP-BUSINESS-BUY-001
title: Browse business stores and use the cart
audience: signed-in buyers of business listings
availability: cart is environment-dependent
---

# Browse business stores and use the cart

## Common questions

- How do I buy from a business store?
- How do I add an item to my cart?
- Why does my cart ask me to accept a new price or quantity?
- Does adding an item reserve it?

## Who this applies to

Buyers viewing listings labeled as business items or **Verified business**.

## Prerequisites

- Sign in before adding items to the cart.
- The current environment must show **Cart** and **Add to cart**. If those controls are absent, business checkout is not available there.

## Pages

- [Business catalog](/stores)
- [Business listing](/listings/{listingId})
- [Cart](/cart)

## Add a business item to the cart

1. Open **Stores** and select **View item** on a business listing.
2. Review the store, price, condition, listed quantity, and public business location.
3. Enter a value from 1 to 999 in **Quantity**.
4. Select **Add to cart**. If you are signed out, select **Sign in to add** first.
5. After the success message **Added to cart**, select **Go to cart**.

## Review and repair the cart

1. Open **Cart** or `/cart`.
2. Use **Increase quantity**, **Decrease quantity**, or **Remove** for each item.
3. Wait for the current price, stock, store, and currency checks.
4. If an item needs attention, use the action shown for that item:
   - **Use {quantity} available** reduces the request to current stock.
   - **Accept current price** updates the saved cart price.
   - **Remove item** removes an unavailable or ineligible item.
5. When finished, select **Clear cart** only if you want to remove every item.
6. If **Continue to checkout** appears, follow [Demo checkout and saved addresses](demo-checkout-and-addresses.md). If the page says **Checkout is not enabled yet**, the cart can only save and validate items in that environment.

## Availability and safety limits

- The cart accepts business listings only. Individual listings use messages and off-platform trades.
- A cart records an observed price and requested quantity. It does not reserve stock and does not guarantee price or availability.
- The application revalidates price and stock before checkout.
- Cart visibility is build-dependent, and the backend cart route must also be enabled. A visible cart that cannot load indicates an environment/runtime mismatch, not a completed purchase.
- Real checkout and real payment are not implemented in this project. The only current checkout flow is an explicitly enabled local demo.
