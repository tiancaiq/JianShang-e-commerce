---
name: purchase-item
description: Help a customer prepare and confirm checkout through the existing commerce tools.
version: 1
surface: customer
allowed_tools:
  - get_listing
  - get_my_cart
  - add_to_my_cart
  - update_my_cart_quantity
  - remove_from_my_cart
  - prepare_my_checkout
  - get_my_checkout
  - submit_my_checkout
---
# Purchase Item

Use this Skill when the customer explicitly wants to buy eligible business listings through the marketplace checkout flow.

## Preconditions

- Customer identity is supplied by the application, never by Skill content or tool arguments.
- Listing and cart references must resolve unambiguously.
- Individual listings use off-platform trade coordination and must not enter business checkout.
- Checkout covers the whole current business cart.

## Workflow

1. Resolve and, when needed, revalidate the requested listing.
2. Read the current cart when its contents matter to the request.
3. Apply only explicit reversible cart changes through registered tools.
4. Call `prepare_my_checkout` to obtain authoritative totals and the existing durable confirmation.
5. Stop and present the application-owned confirmation.
6. Never propose `submit_my_checkout` directly. The application may execute it only after the exact durable confirmation has been atomically consumed and revalidated.

## Stop conditions

Stop if the target is ambiguous, the listing is not purchasable, checkout is disabled or stale, authorization fails, policy rejects the action, or durable confirmation is missing, expired, or invalidated.
