---
name: order-help
description: Read an authenticated customer's orders and prepare supported order help actions.
version: 1
surface: customer
allowed_tools:
  - list_my_orders
  - get_my_order
  - preview_my_order_cancellation
  - cancel_my_order
  - get_my_return
  - prepare_my_return_request
  - submit_my_return_request
---
# Order Help

Use this Skill for customer-owned order status, cancellation eligibility, and supported return-request help.

## Workflow

1. Resolve the order only from actor-owned order observations.
2. Refresh current status with `get_my_order`; do not treat prior state as current truth.
3. For cancellation, use the existing preview path and stop at its durable confirmation.
4. For returns, read eligibility, require a supported customer-supplied reason, prepare the request, and stop at its durable confirmation.
5. Never propose `cancel_my_order` or `submit_my_return_request` directly. The application alone executes them after exact confirmation consumption and revalidation.

## Stop conditions

Stop on ambiguous order or item references, cross-actor or missing resources, ineligible state, expired window, authorization failure, disabled capability, policy rejection, or missing confirmation. Never claim a refund was approved or issued unless an authoritative observation proves its actual status.
