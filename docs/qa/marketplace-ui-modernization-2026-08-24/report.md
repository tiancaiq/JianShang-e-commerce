# Marketplace UI/UX audit and modernization report

Date: 2026-08-24–25  
Verdict: **PASS WITH FOLLOW-UP**

The guest marketplace, public business catalog, authentication dialog, shared listing presentation, account shell, seller workspace, commerce shells, and marketplace assistant presentation now share one responsive design language. The public pages were inspected in the running Angular application with Playwright at 390×844, 768×1024, and 1366×768. The production-like demo-checkout build and the complete Angular unit suite pass.

Runtime update, 2026-08-25: the disconnected frontend was traced to a stale Docker Desktop WSL data-disk attachment (`WSL_E_USER_VHD_ALREADY_ATTACHED`), not an Angular API configuration error. Docker's WSL distribution was released, the engine recovered, and the current frontend image was rebuilt and replaced. Frontend, same-origin API proxy, gateway health, Keycloak discovery, public listing search, and unauthenticated session endpoints now return HTTP 200. The rebuilt UI renders 24 live listings without browser errors. Signed-in buyer, seller, cart, checkout, order, chat, and assistant journeys still need a credentialed browser run.

## Scope and contracts

- Approved UI direction: `docs/mvp/ui/marketplace-ui-redesign.md`, especially Header, Category Rail, Hero, Listing Card Rules, Browse/Search, Listing Detail, Responsive Rules, and UI Acceptance Criteria.
- Roadmap slices preserved: SITE-01, SEARCH-01A, SEARCH-01B, SEARCH-03, SEARCH-05, LIST-08, CHAT-01 through CHAT-04.
- Individual listings remain direct buyer/seller trades. The UI says that payment and delivery are arranged with the seller and does not imply platform protection.
- Business inventory remains visually and verbally distinct. Deferred cart/checkout/order styling is capability-scoped and does not merge the individual trade state model.
- Marketplace Agent V2 remains results-first. Starter prompts only prefill the composer; they do not auto-send or introduce a repeated clarification loop.
- No backend contracts, authorization behavior, feature flags, API payloads, or migrations changed.

## Audit findings

| Priority | Finding | Resolution |
| --- | --- | --- |
| P0 | Gateway/domain requests hung, blocking authenticated end-to-end browser journeys. | Resolved 2026-08-25 by clearing Docker Desktop's stale WSL data-disk attachment, restarting the engine, and rebuilding the current frontend container. Credentialed journeys remain to be exercised. |
| P1 | Mobile marketplace header expanded across multiple rows and pushed inventory far below the fold. | Replaced with compact brand/login/menu row plus search row; tablet now uses the same compact navigation. |
| P1 | The original hero over-emphasized a niche mascot-led visual theme and did not explain the two transaction paths. | Replaced with the “neighborhood exchange” direction and explicit individual local-trade vs business-store lanes. |
| P1 | Mobile marketplace and business-store filters displaced results. | Added accessible Filters triggers, fixed drawers, close controls, and backdrops; results remain primary. |
| P1 | Listing cards obscured seller type and used ambiguous/incorrect condition presentation. | Unified card hierarchy, seller-type labels, accurate condition labels, location/owner context, and plain-text engagement counters. |
| P1 | Loading, empty, and error states relied on decorative mascot treatments and weak recovery actions. | Added neutral skeletons, status copy, retry actions, and clearer empty-state next steps. |
| P2 | Sign-in dialog was visually oversized and Escape did not dismiss it. | Simplified to a single-column account dialog; added Escape dismissal and verified focus returns to Login. |
| P2 | Seller navigation did not collapse on small screens. | Added a semantic mobile Menu control while preserving established route labels and permissions. |
| P2 | Assistant empty state gave no useful orientation. | Added three marketplace starter intents that only prefill the message field. |
| P3 | Component-local listing-detail overrides exceeded the Angular style budget. | Consolidated the cross-surface presentation layer into `frontend/src/marketplace-theme.css`; build is warning-free. |

## Design system

Visual direction: **neighborhood exchange** — calm, useful, local, and transaction-model explicit. It uses a cool neutral canvas, deep ink, sea-green actions, and coral only for local-trade emphasis.

| Token group | Values and use |
| --- | --- |
| Canvas/surface | `#f4f7f6`, `#ffffff`, `#edf4f1`; page depth without decorative noise. |
| Text | `#142f32` ink, `#607275` muted; strong legibility and restrained hierarchy. |
| Primary | `#0d7c75`, dark `#08635e`, soft `#dcefeb`; actions, focus, verified/business states. |
| Secondary | Coral `#df6f4d`, soft `#fff0ea`; individual/local-trade emphasis. |
| Status | success `#18745b`, warning `#a4610a`, danger `#b43b42`, info `#2f659f`. |
| Radius | 8px controls, 14px cards/drawers, 22px feature panels; pills only for compact statuses. |
| Elevation | 1–2px card shadow, 12/32px raised surface, 24/60px modal; borders carry most structure. |
| Typography | Aptos/Segoe UI system stack; compact display headings, readable body copy, numeric price alignment. |
| Focus | 3px sea-green focus-visible ring with 2px offset across links and controls. |
| Responsive | Compact nav at ≤820px; result-first mobile filters; one-column forms/cards at phone sizes. |

The semantic tokens live in `frontend/src/marketplace-theme.css` and map into account/profile/address, listing form/detail, cart, checkout, order, assistant, and seller portal surfaces without changing their domain behavior.

## Rendered verification

Verified in the running app:

- Marketplace home at 1366×768, 768×1024, and 390×844.
- Marketplace navigation open/closed state at mobile/tablet breakpoints.
- Individual marketplace filter drawer, including visible close control and backdrop.
- Business catalog at 1366×768 and 390×844.
- Business filter drawer; mobile results now begin around 586px instead of roughly 1,400px.
- Sign-in dialog semantics and appearance; Escape dismissal verified.
- No horizontal overflow at tested guest viewports.
- No browser console errors on the final mobile business-catalog pass.

Blocked from live browser verification by the gateway:

- Authenticated account, profile, addresses, favorites, listing management, and inbox.
- Cart, checkout, order list/detail, and payment states.
- Seller dashboard, store-item management, inventory, and business orders.
- Authenticated Marketplace Agent V2 conversation and listing attachments.

## Automated verification

- `npm test -- --watch=false`: **752 SUCCESS**.
- Changed-surface subset (marketplace home, business catalog, auth layout, Marketplace Agent V2): **41 SUCCESS**.
- `npm run build:demo-checkout`: **PASS**, including deferred commerce routes and style budgets.
- `git diff --check`: **PASS**; Git only reported the repository's expected LF-to-CRLF working-copy notices.

## Screenshot index

Before:

- `before/marketplace-home-1366x768.png`
- `before/marketplace-home-390x844.png`
- `before/stores-1366x768.png`
- `before/auth-dialog-1366x768.png`

After:

- `after/marketplace-home-1366x768.png`
- `after/marketplace-home-768x1024.png`
- `after/marketplace-home-390x844.png`
- `after/stores-1366x768.png`
- `after/stores-390x844.png`
- `after/auth-dialog-1366x768.png`

## Follow-up acceptance run

When the gateway is healthy, repeat the browser loop at desktop and mobile for: login/register, account/profile/address changes, favorites, individual listing creation/editing, listing chat, seller dashboard/store items, business cart, checkout validation/payment, order history/detail, and Marketplace Agent V2 result attachments/refinement. Treat any auth guard that leaves a blank page or any request that remains indefinitely loading as a release blocker.
