# V2-CART-01 Redis Cart Plan

Status: complete and browser verified on 2026-07-17.

Release: V2.

Requirements: `CRT-01`, `CRT-02`, and `CRT-03`.

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`

## 1. Goal

Give each authenticated buyer one isolated, expiring Redis cart for active
business listings. Buyers can add an item, view the cart, replace quantities,
remove items, and clear the cart.

## 2. Slice Boundary

Included:

- Activate `order-service` as the owner of the `cart:v1:{userId}` namespace.
- Store only listing ID, quantity, observed price, currency, and added time.
- Validate active business-listing eligibility and current available inventory
  before add or quantity replacement.
- Use atomic Redis scripts for per-user cart mutations and version increments.
- Expire inactive carts and recreate an empty cart without order side effects.
- Add authenticated cart APIs, gateway routing, marketplace navigation, a cart
  management page, and an add-to-cart action for business listing details.
- Add service-authenticated product and inventory reads that expose only the
  facts needed by commerce orchestration.

Not included:

- Full-cart price and availability revalidation or warning states.
- Inventory reservation, expiry, release, or commit.
- Checkout, addresses, payment, orders, shipping, refunds, or notifications.
- Individual listings, structured trade offers, or merging trade and order
  state machines.

`V2-CART-02` owns full-cart revalidation and actionable warnings. CART-01
checks eligibility and stock on every quantity write so invalid items cannot
be newly added, but reading a cart does not mutate it or claim checkout
readiness.

## 3. Ownership And Authorization

- `order-service` owns Redis cart documents and all `/api/v1/cart` routes.
- The authenticated JWT subject is the cart owner. No user ID is accepted from
  the browser.
- Product service remains authoritative for seller type, lifecycle status,
  title, media, price, and currency.
- Inventory service remains authoritative for on-hand, reserved, and available
  quantity.
- Internal product and inventory reads require
  `X-Internal-Service-Token`; browser routes cannot call them through the
  gateway.

## 4. Redis Contract

Key:

```text
cart:v1:{userId}
```

Value:

```json
{
  "version": 3,
  "expiresAt": "2026-08-16T12:00:00Z",
  "items": [
    {
      "listingId": "01...",
      "quantity": 2,
      "observedPrice": 19.99,
      "currency": "USD",
      "addedAt": "2026-07-17T12:00:00Z"
    }
  ]
}
```

Rules:

- The default inactivity TTL is 30 days.
- Successful mutations refresh the TTL and `expiresAt`.
- Reads do not refresh expiry.
- A missing or expired key returns an empty version-zero cart.
- Quantity is from 1 through 999 and a cart holds at most 50 distinct items.
- Lua scripts atomically read, mutate, increment version, write, and set TTL.

## 5. API Contract

Buyer routes:

```text
GET    /api/v1/cart
POST   /api/v1/cart/items
PATCH  /api/v1/cart/items/{listingId}
DELETE /api/v1/cart/items/{listingId}
DELETE /api/v1/cart
```

Add request:

```json
{ "listingId": "01...", "quantity": 1 }
```

Quantity replacement request:

```json
{ "quantity": 2 }
```

The cart response returns the cart version, expiry, total quantity, totals by
currency, and display data composed from current product context. Prices used
for totals are the observed prices stored in Redis. CART-01 does not return
`READY`, `PRICE_CHANGED`, or inventory warning states.

Internal routes:

```text
GET /api/v1/internal/store/items/{listingId}/commerce-context
GET /api/v1/internal/inventory/{listingId}/availability
```

Both require `X-Internal-Service-Token`.

## 6. Error Contract

- `CART_ITEM_NOT_ELIGIBLE`: listing is not an active business listing.
- `CART_INSUFFICIENT_STOCK`: requested quantity exceeds current availability.
- `CART_ITEM_LIMIT_EXCEEDED`: the distinct-item limit would be exceeded.
- `CART_DEPENDENCY_UNAVAILABLE`: product or inventory validation is
  unavailable.
- Standard validation and authentication errors use the shared API envelope.

Logs identify operation, actor subject, listing ID, result, and correlation ID
without recording access tokens or unnecessary user data.

## 7. Verification

- Unit tests cover business-only eligibility, quantity/stock rules, owner
  isolation, and dependency failures.
- Redis integration tests cover atomic upsert, replace, remove, clear, version,
  and TTL behavior.
- API tests cover authentication and validation.
- Angular tests cover cart service requests, cart management, navigation, and
  business-only add actions.
- Browser verification signs in, adds the approved business listing, changes
  quantity, reloads the cart, removes the item, and confirms individual
  listings do not expose add-to-cart.

## 8. Implementation Result

- `order-service` owns authenticated cart reads and mutations backed by Redis.
- Product and inventory services expose token-protected commerce context reads.
- The gateway relays cart requests and returns the standard service-unavailable
  envelope for GET, POST, PATCH, and DELETE fallbacks.
- Marketplace business listings expose add-to-cart, the navbar shows total
  quantity, and `/cart` supports quantity replacement, removal, and clear.
- No Flyway migration was added because Redis is authoritative for this slice.
- Browser verification covered add, quantity change, observed totals,
  navigation persistence, clear, empty state, and the absence of cart actions
  on an individual listing.
