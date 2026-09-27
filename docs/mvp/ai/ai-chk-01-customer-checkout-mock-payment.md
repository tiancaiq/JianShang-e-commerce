# AI-CHK-01 Customer Checkout, Mock Payment, and Order Flow

Status: implemented behind an independent default-off rollout flag; automated
and disposable live acceptance determine release readiness.

## Decision

Marketplace Agent V2 may prepare the authenticated customer's whole business
cart and, after an exact durable AI-CONF-01 confirmation, reuse the existing
Order and Payment application workflow. It does not own checkout, payment,
inventory, price, tax, shipping, or order state.

Payment Service, its provider-neutral `PaymentProvider` boundary, and the
configured `FAKE_LOCAL_DEMO_V1` adapter all predate AI-CHK-01. This milestone
does not add or fork a mock payment system; it adds only the narrow Agent
adapter and confirmation/reconciliation safeguards around the same customer
checkout commands already used by the Angular checkout flow.

The implementation adds no AI-specific payment or order shortcut. The flow is:

```text
delegated customer bearer
-> Order cart validation
-> Auth saved-address read
-> Order checkout snapshot and Inventory reservation
-> Agent durable exact confirmation
-> current policy, ownership, cart, checkout, price, and reservation checks
-> atomic confirmation consume
-> Order payment-intent command with the stable action key
-> Payment Service configured fake provider and verified webhook
-> Order outbox consumer and authoritative order
-> owned Order read and grounded Agent response
```

## Capabilities

| Capability | Model input | Surface | Risk | Mutation | Confirmation | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| `prepare_my_checkout` | none | Marketplace customer | 2 | Yes: creates a short-lived checkout/reservation | No; it prepares confirmation | Order/Inventory |
| `get_my_checkout` | referenced checkout ID | Marketplace customer | 1 | No | No | Order |
| `submit_my_checkout` | exact stored checkout ID | Marketplace customer | 3 | Yes | Required; application invocation only | Order/Payment |

All three belong to the existing typed AI-POL-00 registry. Direct model
proposals for `submit_my_checkout` are rejected with
`CONFIRMATION_REQUIRED`. No actor, buyer, account, payment provider, payment
method, or desired outcome exists in a provider-facing argument schema.

The family is independently controlled by
`AGENT_MARKETPLACE_V2_CHECKOUT_ENABLED`, default `false`. Enabling it requires
the Marketplace V2 API, commerce reads, Auth URL, and Order URL. It does not
require or automatically enable Agent cart mutations. Disabling it removes the
family from provider schemas and causes current policy/final execution checks
to fail closed. Normal non-AI checkout is unchanged.

## Preparation and checkout authority

`prepare_my_checkout` first calls Order's authoritative cart validation. An
empty cart, current price mismatch, unavailable quantity, or ineligible item
does not create checkout. The adapter reads the authenticated user's saved
addresses from Auth and selects the existing default address, or the first
saved address when no default is marked. It never asks the model to select an
address and does not expose recipient, phone, address lines, or postal code.
Missing address directs the customer to the existing account workflow.

Order's existing `POST /api/v1/checkouts` owns snapshot creation, totals,
shipping, tax, discounts, expiry, and Inventory reservation. The Agent uses the
validated cart version and a stable `agent-checkout-{invocationId}` idempotency
key. If Order reports an already-active checkout, the adapter reads that owned
checkout and accepts it only when it still matches the current cart.

The customer-visible summary contains item titles, quantities, line totals,
subtotal, shipping, tax, discount, total, currency, status, expiry, and a
masked saved-address summary. It is normalized through an allowlist. Raw Order,
Auth, Inventory, and Payment responses do not reach the model or browser.

Checkout is whole-cart only. A partial request does not silently remove other
cart lines or prepare checkout. Ambiguous `buy that` requests over multiple
references receive a focused clarification.

## Exact confirmation binding

AI-CONF-01 stores the Level 3 `SUBMIT_CHECKOUT` action with:

- authenticated actor, Marketplace V2 session, and originating invocation;
- capability `submit_my_checkout` and contract version;
- exact checkout ID and immutable normalized arguments;
- checkout/cart versions and SHA-256 snapshot fingerprints;
- item identities, catalog versions, quantities, prices, totals, address
  reference/version, reservation reference/status, and expiry inside the
  fingerprints;
- exact total and currency as typed financial facts;
- backend expiry and stable `agent-action-{confirmationId}` action key.

These private bindings remain server-side. The browser receives only the safe
summary and lifecycle needed to render Yes/No controls; version tokens,
fingerprints, target IDs, and action keys are not returned in checkout
confirmation arguments or prose.

`Yes`, `Confirm`, `Go ahead`, and `Do it` resolve the same actor/session pending
record. `No` cancels it; ambiguous `Maybe` preserves it. A bare confirmation in
another session has no authority.

## Revalidation and single use

Before the atomic claim, Agent Service concurrently refreshes the owned Order
checkout and current cart validation. It compares the stored cart version,
cart snapshot, checkout snapshot, amount, currency, reservation state, checkout
state, and expiry. A cart, quantity, catalog-price, address/version, checkout,
or reservation-state change invalidates the old action. Current capability and
authorization are also required.

The confirmation database transition remains the single-use boundary:

```text
PENDING -> CONFIRMED -> CONSUMED
```

or a terminal `CANCELLED`, `EXPIRED`, or `INVALIDATED` transition. Only one
concurrent claim can reach `CONSUMED`. Replays receive terminal prose and do not
invoke `submit_my_checkout` again. AI-POL-00 Level 5 input invalidates pending
authority before any commerce execution.

Order additionally refreshes the exact live Inventory reservation immediately
before creating a payment intent. It verifies reservation ID, checkout binding,
purpose, status, usability, expiry, and exact listing quantities. This owning-
service check prevents a stale or released reservation from reaching Payment
even if state changes after Agent revalidation.

## Mock payment boundary and outcomes

Order creates the payment intent from its immutable owned checkout snapshot.
It supplies buyer, business scopes, amount, currency, checkout version/hash,
and expiry to Payment Service. The Agent supplies none of these facts and
cannot choose the provider or outcome.

Agent Service never calls Payment Service directly. It invokes the existing
buyer-authenticated Order checkout endpoints; Order remains the application
owner that validates the checkout and delegates to Payment Service. Replacing
the local fake adapter with a future approved real provider is therefore a
Payment-owned adapter/rollout change, not an Agent tool or prompt change.

The configured local provider is `FAKE_LOCAL_DEMO_V1`. Its hosted-action
reference remains inside Order/Payment and is never sent to the model. The
existing demo completion endpoint causes Payment Service to execute its normal
provider adapter and verified HMAC webhook path. Payment's event identity and
Order's processed-event and checkout uniqueness constraints deduplicate
callbacks and order creation.

The repository's current fake provider implements deterministic success only;
it has no supported configuration for decline/failure or delayed provider
callback. AI-CHK-01 does not invent one. Agent normalization nevertheless
handles authoritative `FAILED`, `PROCESSING`, timeout, unknown, and delayed
order-confirmation responses without claiming success. Live decline remains a
follow-up for the Payment-owned fake-provider harness.

## Idempotency and reconciliation

- Checkout preparation uses one stable key per Agent invocation.
- Payment intent uses the durable confirmation action key.
- A lost payment-intent response retries only the same Order command with the
  same key, so Payment receives the same idempotent intent identity.
- Order exposes an owned read of the already-bound payment intent so Agent can
  reconcile a timeout without constructing another payment.
- Once demo completion is issued, a lost completion response is reconciled by
  payment-intent lookup; Agent never issues a second completion.
- Payment webhook event dedupe, Order processed-event dedupe, and Order's
  unique checkout-to-order binding preserve one payment effect and one order.
- Order confirmation is polled briefly through the owned checkout reference.
  If asynchronous confirmation is still pending, Agent says not to pay again
  and directs the customer to Orders rather than asserting success.

Only an authoritative owned order detail with confirmed order resolution lets
the Agent say the order was placed. The final total and status come from that
order read.

## Privacy, audit, and persistence

The delegated bearer is application context only and is excluded from prompts,
tool arguments, persistence payloads, and audit hashes. Auth and Order derive
the actor again and use privacy-preserving not-found behavior for cross-user
resources.

Agent tool audit adds only the three narrow checkout tool names. Consequential
audit hashes checkout, confirmation, and action references; the durable
confirmation tables retain the bounded exact binding and lifecycle. Agent
persistence is not authoritative for payment or order status and re-fetches
owning-service state.

No card number, CVV, payment credential, PaymentIntent secret, provider secret,
raw address, fraud field, private reasoning, generic endpoint, direct payment-
state mutation, direct order-state mutation, cancellation, refund, return,
seller action, or Admin action is introduced.

## Verification contract

Focused tests cover capability exposure, flag defaults, provider schemas,
actor-free inputs, cart/address preparation, authoritative totals, exact
fingerprints, cart/price staleness, single-use orchestration, public binding
redaction, stable audit identity, payment failure normalization, timeout
reconciliation, delayed order confirmation, owned PaymentIntent lookup, live
Inventory reservation validation, frontend parsing, and previous AI-POL-00,
AI-COM-00, AI-COM-01, and AI-CONF-01 behavior.

The eval fixture is
`agent-service/evals/ai_chk_01_customer_checkout_mock_payment_v1.json`.
Live acceptance must use a disposable stack and restore the checkout flag to
its tracked default-off state. It must verify cart, checkout, payment, order,
and inventory through owning APIs or databases, not Agent prose alone.

## Deferred

- `AI-ORD-01`: eligible own-order actions;
- `AI-RET-01`: returns and refund requests;
- `AI-SUP-01`: support/report actions;
- `AI-PAY-01`: any future real-provider integration and credential UI;
- a Payment-owned configurable fake decline/delay acceptance mode;
- separately authenticated and permission-scoped Admin AI.
