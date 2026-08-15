# V2-CART-REL-P0-03 Cart Runtime Reconciliation

## Status

Cart-scoped source, real-Redis, local runtime, and multi-business browser
acceptance are green on 2026-08-03. The overall repository candidate is not yet
release-green because unrelated Order migration and Product/Agent architecture
or whitespace gates still fail.

## Runtime mismatch

The cart implementation was present and the normal production Angular
environment already enabled `cart`. The running demo had drifted from that
source boundary:

- the frontend container had been built from an AI-specific configuration whose
  cart capability was disabled;
- the demo Compose default allowed the frontend build configuration to be
  selected indirectly instead of pinning this cart candidate;
- the cart gateway route was disabled; and
- the dedicated Order, Inventory, and Redis cart runtime was not attached.

`docker-compose.cart-runtime.yml` is the bounded reconciliation overlay. It
pins the normal `production` frontend build, enables only the cart gateway
route, and attaches the existing Order cart, Inventory availability, and
persistent Redis implementations. Checkout, payment, order-read, cancellation,
fulfillment, shipping, and notification capabilities remain disabled.

## Verified journey

- an active BUSINESS listing renders the store purchase box and Add to cart;
- an INDIVIDUAL listing remains trade/chat-only and has no cart action;
- the authenticated header exposes the cart link and total-quantity badge;
- cart quantity changes persist through refresh and sign-out/sign-in;
- store name, slug, verification, and public location group the line;
- different store slugs render different groups, while lines from one store
  share a group without trusting the display name;
- removing the last line from one store removes only that group;
- the badge is the total quantity across all store groups;
- paused/unavailable, price-change, reduced-stock, and out-of-stock states fail
  closed and require explicit remove, price acceptance, or quantity repair;
- no repair is automatic and no uncertain mutation is replayed by the client;
- checkout stays absent and the ready state says `Cart checks passed`.

The shared Shen listing was returned to active status, its original 1.00 USD
price, and eight available units. The second fixture was returned to active
status, 7.50 USD, and twelve available units. The acceptance buyer retains one
line from each store at quantity one; both lines pass current validation.

## Deterministic second business fixture

`tools/cart_second_business_fixture.mjs` uses supported local service
boundaries rather than manual database edits:

1. A false-default, service-authenticated Auth bootstrap restores the approved
   business, active store, owner membership, and immutable verification event.
2. The script signs in as the existing local demo seller, reuses the fixed
   business SKU, and uses Product seller lifecycle/media APIs to restore one
   active item with one confirmed image.
3. It uses Inventory initialize/adjust APIs to restore twelve on-hand units.
4. It verifies the second owner cannot manage the first business listing.

Stable records:

- business application `01KZCARTB00000000000000001`;
- business `01KZCARTB00000000000000002`;
- store `01KZCARTB00000000000000003`, slug `harbor-cart-supply`;
- owner `01D00000000000000000000001`;
- listing SKU `MSB-CART-B-FIXTURE` and current listing
  `01KZ3CF59Z42DG2M0AZ8FQ1729`.

Auth upserts fixed keys in one transaction. Product lookup uses the unique
business SKU and never creates a replacement when it exists. Confirmed media
is reused, and Inventory adjusts only when the target differs. Two consecutive
runs returned the same IDs, one confirmed image, and twelve available units.

Public-store checks prove the fixtures have different business IDs, store IDs,
store slugs, and owner contexts. Mutual Product management checks return hidden
403 responses. A separate buyer session sees an empty cart while the acceptance
buyer retains both groups.

## Multi-business browser evidence

The normal `production` frontend at `http://localhost:4200` passed:

- add one Shen item and one Harbor item, producing exactly two groups;
- independently update quantities to two and three, producing badge five;
- refresh and sign out/sign in with both groups and quantities preserved;
- reduce only Harbor stock to two: Shen remains Ready and Harbor alone offers
  the explicit quantity repair;
- change only Harbor price to 8.00 USD: Shen remains Ready and Harbor alone
  offers explicit price acceptance;
- remove Shen's final line: Harbor remains as the only group and badge one;
- restore both fixtures and the buyer cart to one line per store, badge two;
- verify no checkout, payment, order, cancellation, fulfillment, shipping, or
  notification action is rendered.

## Release gates

The local real-Redis gate executed all six `RedisCartRepositoryTests`: atomic
concurrent mutation, deterministic successful replay and payload conflict,
buyer-scoped keys, failed stale-version retry, and synchronized cart/command
expiry. Auth and Product fixture/provenance tests also executed against
disposable MySQL.

Before calling the repository release-green, the owning non-cart slices must
also clear these independently reproduced gates:

- the full Order package currently fails ten MySQL tests while applying the
  parked `V6__create_order_cancellation_request_foundation.sql` migration; its
  `2099-01-01 00:00:00.000000` value is rejected for `effective_from`;
- `tools/architecture_checks.py` currently reports the pre-existing Product
  migration `V202607192010__seed_trade_completion_demo_listing.sql` referencing
  the Chat schema, plus unrelated Product whitespace findings;
- Agent architecture/whitespace findings remain owned by the Agent lane.

Neither unrelated file is part of this cart reconciliation.

## Local reproduction

```powershell
.\mvnw.cmd -pl auth-service,order-service,inventory-service,api-gateway -am -DskipTests package
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml config --quiet
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml build auth-service api-gateway frontend
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d cart-redis auth-service inventory-service order-service api-gateway frontend
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml ps
$env:LOCAL_DEMO_HARBOR_PASSWORD='<runtime-only password>'
node tools/cart_second_business_fixture.mjs
```

Open `http://localhost:4200`. The overlay is intentionally cart-only; do not
enable deferred commerce flags while running this acceptance path.
