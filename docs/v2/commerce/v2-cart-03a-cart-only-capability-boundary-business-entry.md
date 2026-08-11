# V2-CART-03A Cart-Only Capability Boundary And Business Add-To-Cart Entry

Status: implemented and locally deployed with cart enabled in the production
frontend build; checkout remains disabled.

Release: V2.

Requirements: `CRT-01`, `CRT-02`, `CRT-03`, and `CRT-04` public entry
stabilization.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`

## Goal

Expose cart as its own opt-in capability without enabling checkout, then add
the first public marketplace add-to-cart entry for active business listing
detail pages.

## Boundary

Included:

- Gateway `cart` and `checkout` namespaces are independently false by default.
- Authenticated `/api/v1/cart/**` relays to Order Service only when the cart
  gateway flag is enabled.
- Checkout routes remain disabled unless the checkout gateway flag is enabled.
- The Angular cart route, navbar badge, and listing-detail purchase box are
  controlled by one frontend cart capability.
- Production and `demo-cart` Angular builds enable cart only; checkout stays
  disabled. Development remains disabled for controlled source work.
- Business listing detail pages render a compact purchase box with display
  price, store identity, quantity `1..999`, Add to cart, added/error state,
  and a Go to cart link after success.

Excluded:

- Checkout creation, payment, order confirmation, inventory reservation, and
  shipping.
- Cart controls on listing cards.
- Any cart action for individual listings.
- Any browser-supplied actor, price, stock, or business authority.

## Rules

- Guests who click Add to cart use the existing marketplace login flow with
  the current return URL.
- Authenticated cart mutations use the existing `CartService`; successful adds
  update the shared cart state so the navbar count can refresh.
- Backend cart APIs remain authoritative for listing eligibility, current
  price, and stock. The public listing price is display context only.
- Failed or uncertain add mutations are not replayed automatically.
- Disabled frontend routes redirect silently and disabled gateway routes return
  not found without calling Order Service.

## Verification

Required focused checks:

- Gateway cart enabled while checkout disabled: cart relays bearer token,
  strips spoofed identity headers, requires CSRF for POST, propagates
  correlation, and checkout remains disabled.
- Gateway defaults: cart and checkout are both disabled.
- Frontend disabled configuration: cart route/nav/listing purchase box are
  absent and make no cart requests.
- Frontend opt-in: business listing detail renders the purchase box, clamps
  quantity to `1..999`, sends only listing ID and quantity, refreshes cart
  state after success, and shows no individual listing cart action.
- Production and `demo-cart` Angular builds succeed.

## Implementation Notes

This slice intentionally does not change Order Service, Product Service,
Inventory Service, or checkout behavior. It only opens the already implemented
cart contract through independent gateway/frontend capability gates.
