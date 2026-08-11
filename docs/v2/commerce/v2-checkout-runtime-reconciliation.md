# V2 Checkout Runtime Reconciliation

## Scope

This bounded local runtime activates the already-approved V2 purchase path:

`cart -> checkout -> atomic inventory reservation -> fake payment -> confirmed order`

It reuses V2-IAM-01, V2-CART-03, V2-INV-02, V2-CHK-01, V2-PAY-01,
V2-ORD-01A, and V2-ORD-02/02B contracts. It does not activate cancellation,
seller acceptance, fulfillment, shipping, tracking, notifications, refunds,
returns, reviews, coupons, payouts, or a live payment provider.

## Runtime boundary

`docker-compose.cart-runtime.yml` is the checkout overlay for
`docker-compose.demo.yml`. It pins:

- MySQL `8.4`;
- Angular build configuration `demo-checkout`;
- checkout lifetime `PT15M`;
- tax adapter `ZERO_LOCAL_DEMO_V1`;
- shipping adapter `FREE_LOCAL_DEMO_V1`;
- policy version `LOCAL_DEMO_V1`;
- the fake payment provider, HMAC webhook verification, durable payment outbox,
  and local HTTP payment-event transport;
- buyer address, cart, checkout, buyer order, and read-only business-order
  routes.

Payment routes remain internal to Order Service. The gateway does not expose
Payment Service or Inventory Service directly. Cancellation, seller acceptance,
inventory-management UI, notifications, and unrelated discovery routes remain
off.

## Reconciliation fixes

Order Service V7 closes the post-purchase duplicate-order trap. Checkout
creation persists a durable cart reconciliation command containing the exact
cart version and per-line mutation identities. After the checkout is durably
`COMPLETED`, a scheduled worker uses one Redis script to remove untouched
purchased lines. Lines added or changed after checkout began are preserved,
replay is harmless, and Redis failure retries without affecting the confirmed
order. Checkout and order groups now snapshot and return the store name; old
rows use a neutral `Store` fallback instead of exposing a raw store ID heading.
The seller queue identifies the redacted party as `Marketplace buyer` next to
the buyer-visible order number without adding buyer identity data.

When the confirmation poll observes the durable order, Angular starts a short,
bounded background refresh of the shared cart signal. The header badge updates
as soon as Redis reports either the reconciled empty cart or a buyer-modified
newer cart. Navigation to the confirmed order remains immediate, and a delayed
or failed cart refresh cannot change the payment or order result.

Order migration V6 previously seeded `2099-01-01` into MySQL
`TIMESTAMP(6)`. MySQL's timestamp range ends in 2038, so a clean MySQL 8.4
startup failed with error 1292. The forward-looking policy date is now
`2037-01-01`; the policy contract and cancellation-disabled runtime are
unchanged.

Checkout reservation deadlines are truncated to microsecond precision before
they are persisted or sent to Inventory Service. This matches MySQL
`TIMESTAMP(6)` and keeps the Inventory idempotency payload identical on safe
replay. A repeated create also reconciles a durable checkout left in
`RESERVING`; it never creates a second checkout or reservation.

The pinned runtime also exposed three internal contract mismatches that the
focused test setup did not previously exercise:

- Payment now finishes its parent intent query before loading child business
  scopes, which is safe on the MySQL transaction connection.
- Order's Payment response DTO explicitly ignores Payment-owned audit fields
  such as `createdAt` and `updatedAt` while still validating every authoritative
  checkout, amount, currency, expiry, status, and action field.
- Approved legacy deterministic business IDs remain opaque fixed-length
  uppercase IDs at Payment, Order event, and seller-order boundaries; canonical
  ULID validation remains in place for checkout, payment-intent, order, buyer,
  and business-order IDs.

## Local reproduction

```powershell
.\mvnw.cmd -pl auth-service,inventory-service,order-service,payment-service,api-gateway -am package -DskipTests
npm.cmd --prefix frontend run build:demo-checkout
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml config --quiet
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --build auth-service api-gateway frontend
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d inventory-service payment-service order-service
$env:LOCAL_DEMO_HARBOR_PASSWORD='<runtime-only password>'
node tools/cart_second_business_fixture.mjs
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml ps
```

For a frontend-only rebuild after this runtime is healthy, keep both Compose
files and suppress dependency reconciliation:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --no-deps --build frontend
```

Do not rebuild `frontend` from `docker-compose.demo.yml` alone while using the
`demo-checkout` configuration. That can leave the V2 UI enabled while the
gateway is recreated with its false-by-default commerce flags and disabled
Order Service URL.

Open `http://localhost:4200`. Use the local demo buyer and seller identities
defined in `infra/keycloak/realm-msb-local.json`. The payment action is visibly
labelled **Local demo payment** and **No real money will be charged**.

## Deterministic fixture state

The Harbor fixture remains listing `01KZ3CF59Z42DG2M0AZ8FQ1729`, business
`01KZCARTB00000000000000002`, store `01KZCARTB00000000000000003`, price
`7.50 USD`, and twelve on-hand units before a purchase. The Shen fixture remains
listing `01KXQMEH9KBPH5S7DPBFM0VBJ5`, price `1.00 USD`, and eight on-hand units
before a purchase. A successful two-item acceptance purchase commits one unit
from each fixture; rerunning the fixture script restores Harbor to twelve.
The browser acceptance cleanup restored Shen to eight with an auditable
`STOCK_COUNT_CORRECTION` movement and inventory outbox event because that
fixture belongs to a separate seller identity.

## Browser-verified acceptance

The pinned MySQL 8.4 runtime completed checkout
`01KZ3THFEJXYQ5KFB58HWR6F5S`, fake payment intent
`01KZ3THQ5Q9R4PGBAK337ETBE9`, and confirmed buyer order
`01KZ3VAN5Z44XCS4D3WBWEA863` for `8.50 USD`. The buyer UI showed two immutable
store groups, the address snapshot, zero local-demo tax and shipping, explicit
no-real-money copy, confirmed payment, order history, and order detail.

Replaying the exact authenticated `payment.succeeded` event returned
`REPLAYED`; database cardinalities remained one order, one processed payment
event, and two business-order groups. A second signed-in buyer received safe
not-found states for guessed checkout and order IDs. The Harbor owner saw only
the Harbor `7.50 USD` group in the seller queue and detail; its attempt to list
the Shen business scope returned not found. Guest checkout redirected to the
marketplace sign-in route. The deployed seller filter contains no shipping or
cancellation states, and checkout exposes no cancellation control.

## Known repository-wide gates

The checkout-focused suites and runtime are independent of the pre-existing
Product/Agent working-tree changes. The full Order suite also contains disabled
seller-acceptance concurrency tests outside this runtime boundary; seller
acceptance remains explicitly off here.
