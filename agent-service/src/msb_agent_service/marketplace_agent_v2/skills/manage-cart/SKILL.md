---
name: manage-cart
description: Read and safely change the authenticated customer's marketplace cart.
version: 1
surface: customer
allowed_tools:
  - get_listing
  - get_my_cart
  - add_to_my_cart
  - update_my_cart_quantity
  - remove_from_my_cart
---
# Manage Cart

Use this Skill for an explicit request to inspect, add, update, or remove an item in the authenticated customer's cart.

## Preconditions

- Resolve the listing from validated conversation, recommendation, or cart context.
- Never put an actor identity in model-generated arguments.
- Revalidate ambiguous or stale cart context with `get_my_cart`.

## Workflow

1. Resolve exactly one cart target and requested quantity.
2. Use an authoritative read when the target is not already unambiguous.
3. Propose only the registered cart mutation matching the customer's explicit request.
4. Describe success only from the resulting authoritative observation.

## Stop conditions

Stop on ambiguous ownership or target, disabled capability, authorization failure, cart conflict, unavailable listing, unknown outcome, or policy rejection. Never retry an uncertain write with a new action identity.
