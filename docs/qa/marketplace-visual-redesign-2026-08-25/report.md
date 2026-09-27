# Marketplace visual redesign report

Date: 2026-08-25  
Verdict: **PASS WITH FOLLOW-UP**

## Scope

This visual milestone implements `MARKETPLACE-VISUAL-REDESIGN-2026-08-25` across the approved public, marketplace-account, business-seller, admin, and marketplace-assistant surfaces. It visually supports the existing `SITE-00`/`SITE-01`, `IAM-02`, `IAM-04`, `IAM-06`, `LIST-07`, `BUS-LIST-06`, `SEARCH-01`, `SEARCH-01A`, `SEARCH-01B`, and `SEARCH-03` slices without changing their contracts or state machines.

No backend endpoint, persistence contract, API schema, database migration, authorization rule, cart/order/trade state transition, or feature-flag default changed.

## Visual direction

The new direction is a **Sunday neighborhood market**: paper tags, awnings, shelves, parcels, market totes, and a compact shopping scout. It is friendly and illustrated without obscuring the distinction between individual trades and business checkout.

- Dark plum ink and navigation establish contrast and continuity.
- Rose is the primary action color; lavender, blush, mint, peach, and sky are supporting surfaces.
- Trebuchet MS is the display face; Segoe UI/Aptos remain the readable system body stack.
- Cards use restrained asymmetric radii, fine plum-tinted borders, warm white surfaces, and low shadows.
- Admin keeps the same palette but uses a quieter premium-light treatment with less decoration.
- Seller uses a plum navigation rail and illustrated light workspace.

The shared token layer is in `frontend/src/marketplace-theme.css` and is loaded from `frontend/src/styles.css`.

## Illustration system

Six lightweight, local SVGs were created in `frontend/public/assets/brand/illustrations/`:

- `market-stall.svg`: marketplace hero and missing-listing imagery
- `shopping-scout.svg`: marketplace assistant and browse guidance
- `empty-cart.svg`: empty cart
- `empty-orders.svg`: orders and admin operations motif
- `seller-studio.svg`: seller dashboard and seller catalog empty states
- `auth-market.svg`: authentication and account welcome states

The assets share one soft-flat construction, plum outline, rounded geometry, and the same supporting palette. No remote image dependency was added.

## Major surfaces completed

- Marketplace navigation, search, responsive menu, hero, categories, filters, listing cards, and empty/error presentation
- Business stores landing and item discovery
- Public listing detail missing-image state
- Marketplace and portal authentication
- Marketplace account overview
- Seller shell, dashboard, and business catalog state
- Admin shell, review dashboard, and representative data table
- Empty and filled cart, checkout review, and order history
- Marketplace assistant welcome, illustration, and supported starter prompts while preserving results-first behavior
- Shared brand mascot, loading, empty-state, and gallery components

## Quality-gate findings resolved

- Removed horizontal overflow from marketplace, seller, cart, and checkout mobile layouts.
- Removed a duplicate seller-shell `<h1>` so each routed seller page owns its primary heading.
- Preserved established UI copy contracts used by regression tests (`Your cart is empty`, `Business Account`, and `No confirmed orders yet.`).
- Preserved loading, empty, error, disabled, validation, and responsive states.
- Preserved minimum 44px commerce form/action targets and reduced-motion behavior where animation was added.
- Kept individual-trade language separate from verified-business cart and checkout language.

## Browser evidence

Before screenshots are in `before/`; final screenshots are in `after/` next to this report.

Tested viewport sizes:

- 1440 × 900 desktop
- 1366 × 768 desktop/laptop
- 768 × 1024 tablet
- 390 × 844 mobile

Representative final evidence:

- `after/marketplace-home-1440x900.png`
- `after/marketplace-home-768x1024.png`
- `after/marketplace-home-390x844.png`
- `after/stores-1366x768.png`
- `after/stores-390x844.png`
- `after/listing-detail-1366x768.png`
- `after/login-1366x768.png`
- `after/login-390x844.png`
- `after/account-1366x768.png`
- `after/seller-dashboard-1366x768.png`
- `after/seller-dashboard-390x844.png`
- `after/seller-items-1366x768.png`
- `after/admin-dashboard-1366x768.png`
- `after/admin-businesses-1366x768.png`
- `after/cart-empty-1366x768.png`
- `after/cart-filled-1366x768.png`
- `after/cart-filled-390x844.png`
- `after/checkout-1366x768.png`
- `after/checkout-390x844.png`
- `after/orders-1366x768.png`

The cart/checkout evidence uses a real authenticated buyer and the live local backend. `frontend/proxy.visual-evidence.json` connects the alternate checkout-enabled Angular build to the running demo gateway, resolving the same-origin disconnect encountered during testing. The test clears the cart in `finally` and does not create or pay for an order.

## Automated verification

- Angular unit suite: **752/752 passed**
- Playwright seller/admin visual evidence: **2/2 passed**
- Playwright real-backend commerce evidence: **1/1 passed**
- Production Angular build: **passed**, initial bundle 361.49 kB raw / 96.11 kB estimated transfer
- `demo-checkout` Angular build: **passed**, initial bundle 361.52 kB raw / 96.14 kB estimated transfer
- `git diff --check`: **passed** (line-ending notices only)

## Files and contracts

Primary implementation areas:

- `frontend/src/marketplace-theme.css`
- `frontend/src/app/layout/marketplace-layout/`
- `frontend/src/app/layout/seller-layout/`
- `frontend/src/app/layout/admin-layout/`
- `frontend/src/app/features/marketplace/`
- `frontend/src/app/features/stores/`
- `frontend/src/app/features/auth/`
- `frontend/src/app/features/account/`
- `frontend/src/app/features/seller/`
- `frontend/src/app/features/business/business-store-items.component.ts`
- `frontend/src/app/features/admin/admin-dashboard.component.ts`
- `frontend/src/app/features/cart/cart.component.ts`
- `frontend/src/app/features/checkout/`
- `frontend/src/app/features/orders/order-list.component.ts`
- `frontend/src/app/features/agent/agent-marketplace-v2-page.component.ts`
- `frontend/src/app/shared/components/ui/`
- `frontend/e2e/visual-redesign-evidence.spec.ts`
- `frontend/e2e/visual-commerce-evidence.spec.ts`

Contract changes: none.  
Migrations added: none.

## Known follow-up

The local production profile keeps buyer checkout and the marketplace assistant disabled by their approved feature flags. Commerce was therefore verified using the repository's `demo-checkout` build against the real backend. The assistant source and shared visual states were redesigned and covered by the full unit suite, but live assistant browser evidence remains deferred until its currently unavailable local agent runtime and rollout flags are enabled.

