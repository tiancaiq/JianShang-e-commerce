# V2 Stripe sandbox runtime and verification

This runtime is test-mode only. `FAKE_LOCAL_DEMO_V1` remains the default for
local deterministic development, normal CI, and `V2-COM-RC-01`.

## Fake provider

Run the existing Commerce RC overlay without the Stripe overlay. No Stripe
configuration is required:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d
```

## Stripe test provider

Payment Service uses `stripe-java` `31.4.0`, whose SDK API pin is
`2026-02-25.clover`. Stripe mode fails startup if the configured API version
drifts from that SDK pin.

Inject these values at runtime; do not place them in Git or committed `.env`
files:

```text
PAYMENT_STRIPE_SECRET_KEY=sk_test_...
PAYMENT_STRIPE_PUBLISHABLE_KEY=pk_test_...
PAYMENT_STRIPE_WEBHOOK_SECRET=whsec_...
```

Then apply the explicit overlay:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml -f docker-compose.stripe-sandbox.yml up -d
```

Stripe mode rejects a missing key, a live secret key, a live publishable key,
or a missing webhook secret during Payment Service startup. The public webhook
URL is exactly:

```text
POST /api/v1/webhooks/payments/STRIPE_TEST_V1
```

For local webhook delivery, forward Stripe sandbox events to the Gateway URL
with the Stripe CLI and inject the CLI-issued `whsec_...` value at runtime.
Subscribe only to:

```text
payment_intent.succeeded
payment_intent.payment_failed
payment_intent.processing
payment_intent.canceled
refund.created
refund.updated
refund.failed
```

The browser callback is never authoritative. The checkout continues polling the
owned confirmed-order endpoint until verified Payment state and the Payment
outbox cause Order to confirm exactly once.

## Credential-backed CI

Configure the three repository secrets above and manually dispatch
`Stripe Sandbox Integration`. The workflow refuses non-test key prefixes and is
separate from the deterministic Commerce RC workflow. Its provider gate uses
Stripe test payment methods to verify idempotent intent creation, server-owned
amount/currency, a successful card, a declined card, an authentication-required
card, a full refund, and a bounded partial refund. Each run uses unique sandbox
correlation IDs so reruns do not attempt to reconfirm old test payments.

The production-like frontend CSP allows only Stripe's documented Stripe.js,
API, and authentication-frame origins required by Payment Element. Card fields
remain inside Stripe-owned frames.

## Operational checks

- Search logs by internal checkout/payment/refund IDs. Do not paste client
  secrets, API keys, signature headers, or raw payloads into logs or tickets.
- A Stripe call timeout is retried with the same internal/provider idempotency
  key. Do not create a new checkout to work around an uncertain provider result.
- Payment and refund reconciliation is enabled only in the Stripe overlay.
- A cancellation or return remains refund-pending until Stripe reports final
  refund success. Inventory restoration/restocking remains independently
  idempotent in Order/Inventory.
- Disable the Stripe overlay and return to fake mode as the sandbox kill switch;
  do not switch modes while non-terminal Stripe payments or refunds remain.
