---
document_id: HELP-INTERNAL-COVERAGE-001
title: Customer help coverage and code evidence
internal: true
---

# Customer help coverage and code evidence

This file is for maintainers. It is not a customer help article and is excluded from the public retrieval corpus. It records the implementation evidence checked on 2026-09-28 and the gaps that should prevent unsupported promises.

## Scope and method

The review used current source as availability evidence. Approved MVP documents were used for invariants and requirement IDs, not as proof that a customer workflow is exposed. Old README/tutorial/planning material was not used as availability evidence.

Repository-wide areas inspected:

- Repository, documentation, frontend, gateway, Auth, Product, and Agent `AGENTS.md` instructions.
- Angular route tree, layouts, page components, services, models, capability tokens, all committed environment variants, build replacements, and route tests.
- Gateway route conditions and default feature properties.
- Customer-facing controllers and configuration in Auth, Product, Chat, Order, Inventory, Payment, and Notification services.
- Local/demo Docker Compose feature wiring, especially `docker-compose.demo.yml`, `docker-compose.demo-ai-main.yml`, and `docker-compose.cart-runtime.yml`.
- Approved requirements, architecture, database, API contract, and roadmap entries for IAM, IND, LIST, SEARCH, CHAT, BUS, V2 commerce, reports, appeals, support, and optional Agent behavior.
- Existing uncommitted changes were inspected with `git status`; this task adds only `docs/help/` files.

## Availability matrix

| Capability | Production frontend | Development frontend | Explicit build | Gateway/service default | Help treatment |
| --- | --- | --- | --- | --- | --- |
| Public individual browse/search | On | On | All | Routed as core Product API | Core |
| Public business catalog/store profiles | On | On | All | Routed as core Product/Auth API | Core |
| Account/profile, likes, individual selling, chat, reports, appeals, support | On | On | All | Core routes/services | Core |
| Cart | On | Off | `demo-cart`, `demo-cart-ai-discovery`, `demo-checkout` | Gateway cart is off unless enabled; Order/Redis runtime required | Conditional, “if shown” |
| Buyer addresses | Off | Off | `demo-checkout` | Gateway route off unless enabled | Demo-only |
| Checkout and buyer orders | Off | Off | `demo-checkout` | Gateway and Order capabilities default off | Demo-only |
| Order cancellation | Off | Off | `demo-checkout` | Gateway and processing default off | Demo-only |
| Returns | Off | Off | `demo-checkout` | Gateway and return processing default off | Demo-only |
| Business orders and fulfillment | Off | Off | `demo-checkout` | Gateway and Order capabilities default off | Demo-only |
| Notifications | Off | Off | `demo-checkout` | Gateway, read API, and commerce events default off | Demo-only; in-app only |
| Seller inventory UI | Off | Off | Off in every committed environment | Gateway inventory default off | Not documented as usable |
| Marketplace Assistant | Off | Off | `demo-ai`, `demo-ai-discovery`, `demo-agent-v2`, `demo-cart-ai-discovery` | Multiple independent gateway/Agent switches default off | AI-demo-only |
| Real payment/provider checkout | Absent | Absent | Absent | Payment intents/demo completion independently off; current enabled path is fake local demo | Explicitly unavailable |

The committed production Angular build enables the cart UI while the gateway's cart route defaults to disabled. Deployments must align the frontend build, gateway flag, Order service, Redis, and dependencies. The public article describes the cart as environment-dependent rather than implying that UI visibility alone proves end-to-end availability.

## Article evidence map

### HELP-BROWSE-001 — Browse and search

- Routes/components: `frontend/src/app/app.routes.ts`; `frontend/src/app/features/marketplace/marketplace-home.component.ts`; `frontend/src/app/features/marketplace/components/marketplace-hero-banner.component.ts`; `marketplace-filter-panel.component.ts`; `marketplace-sidebar.component.ts`; `marketplace-product-card.component.ts`; `frontend/src/app/features/stores/business-stores.component.ts`; `public-store-profile.component.ts`; `frontend/src/app/features/marketplace/public-listing-detail.component.ts`.
- Client/API: `frontend/src/app/core/services/listing.service.ts`; `frontend/src/app/core/services/business-store.service.ts`; Product public marketplace/store search and public detail handlers in `product-service/src/main/java/com/msb/ecom/product_service/controller/ListingController.java`; public store reads in `auth-service/src/main/java/com/msb/ecom/auth_service/controller/BusinessStoreController.java`.
- Verified distinction: marketplace results are filtered to `INDIVIDUAL`; store results are filtered to `BUSINESS`.

### HELP-TRADE-001 and HELP-MESSAGES-001 — Individual purchase path and chat

- Routes/components: `public-listing-detail.component.ts`; `conversation-shell.component.ts`; `floating-chat.component.ts`.
- Client/API: `frontend/src/app/core/services/chat.service.ts`; `chat-service/src/main/java/com/msb/ecom/chat_service/controller/ConversationController.java`; `ConversationService.java`; `ConversationRepository.java`.
- Verified behavior: conversation start is individual-listing-only; self-chat is rejected; text is limited to 2,000 characters; seller mark-done targets the buyer in the conversation; buyer confirmation closes the listing and makes the conversation read-only; off-platform payment disclosure is present in the UI.

### HELP-BUSINESS-BUY-001 — Business catalog and cart

- Routes/components: `app.routes.ts`; `public-listing-detail.component.ts`; `frontend/src/app/features/cart/cart.component.ts`; `cart.capability.ts`; `marketplace-navbar.component.ts`.
- Client/API: `frontend/src/app/core/services/cart.service.ts`; `order-service/src/main/java/com/msb/ecom/order_service/controller/CartController.java`; cart policy in `order-service/src/main/resources/application.properties`.
- Flags/runtime: all `frontend/src/environments/environment*.ts`; `api-gateway/src/main/resources/application.properties`; conditional routes in `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`; `docker-compose.cart-runtime.yml`.
- Verified behavior: business items only, quantity 1–999, maximum 50 distinct cart items from backend policy, saved/observed prices are revalidated, and cart addition does not reserve stock.

### HELP-CHECKOUT-001 — Addresses and demo checkout

- Routes/components: `frontend/src/app/features/account/address-book.component.ts`; `frontend/src/app/features/checkout/checkout-review.component.ts`; `checkout-detail.component.ts`.
- Client/API: `address-book.service.ts`; `checkout.service.ts`; `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AddressController.java`; `order-service/src/main/java/com/msb/ecom/order_service/controller/CheckoutController.java`.
- Flags/runtime: `environment.demo-checkout.ts`; gateway checkout/address flags; Order checkout/payment integration properties; Payment internal intent/demo completion controllers; `docker-compose.cart-runtime.yml`.
- Verified behavior: 20-address limit; local-demo zero tax/free shipping adapters; checkout lifetime configured as 15 minutes in the committed demo runtime; payment UI repeatedly says no real money is charged.

### HELP-ORDERS-001 — Buyer orders, cancellation, returns, disputes

- Routes/components: `frontend/src/app/features/orders/order-list.component.ts`; `order-detail.component.ts`; `order-dispute.component.ts`.
- Client/API: `order.service.ts`; `order-dispute.service.ts`; Order `BuyerOrderController.java`, `OrderCancellationController.java`, `BusinessOrderReturnController.java`, and `OrderDisputeController.java`.
- Flags/runtime: buyer views, cancellation processing, returns, return restocking, refund enablement, and gateway flags in service properties and `docker-compose.cart-runtime.yml`.
- Verified behavior: cancellation is whole-order and limited to pre-acceptance; returns are whole business-order-group and post-delivery; demo runtime return window is 30 days; disputes do not automatically create refunds or enforcement.
- Route note: buyer dispute routes are present in Angular even when buyer order routes are redirected. They still require a real eligible order/group and are documented only with the demo order workflow.

### HELP-FAVORITES-001 — Likes

- Routes/components: `public-listing-detail.component.ts`; `frontend/src/app/features/account/liked-listings.component.ts`; marketplace navbar/account dashboard.
- Client/API: like, unlike, engagement, and liked-listing calls in `listing.service.ts`; handlers in Product `ListingController.java`.
- Verified behavior: sign-in required for mutation/list; likes apply on the shared listing detail and do not reserve anything.

### HELP-ACCOUNT-001 — Account and profile

- Routes/components: `frontend/src/app/features/auth/login.component.ts`; `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`; marketplace navbar; account dashboard; `profile.component.ts`.
- Client/API: `auth.service.ts`; `user-profile.service.ts`; Auth `AuthController.java`, user profile controller/service, and avatar storage path.
- Verified behavior: native sign-in/register UI, display name/phone edit, disabled email, PNG/JPEG/WebP avatar up to 5 MB, logout. No password-reset/change or email-change UI was found.

### HELP-SELL-IND-001 — Individual seller and listings

- Routes/components: `individual-seller-activation.component.ts`; `account-listings-entry.component.ts`; `listing-management.component.ts`; `listing-draft-form.component.ts`; `listing-draft-form.helpers.ts`; `listing-media-upload.service.ts`.
- Client/API: `individual-seller.service.ts`; `listing.service.ts`; Auth `IndividualSellerController.java`; Product `ListingController.java` and listing/media/moderation services.
- Verified behavior: active seller profile required; off-platform terms accepted; individual route forces `INDIVIDUAL`; one or more saved images required for review; 10-image maximum and 10 MB per image; pending review is locked; changes requested/active/closed can be edited under the component rules; individual close action exists.

### HELP-SELL-BUS-001 — Business onboarding, store, and catalog

- Routes/components: seller layout/dashboard; `business-application.component.ts`; `business-account.component.ts`; `business-store.component.ts`; `business-store-items.component.ts`; business mode of `listing-draft-form.component.ts`.
- Client/API: `business-application.service.ts`; `business-store.service.ts`; `listing.service.ts`; Auth `BusinessApplicationController.java` and `BusinessStoreController.java`; Product store-item routes in `ListingController.java`.
- Verified behavior: save then submit application; approved business creates store context; store profile fields; business SKU/quantity; draft publish, active pause, paused edit/relist; active item must be paused before edits.
- Explicit exclusion: `business-inventory.component.ts` and `InventoryController.java` exist, but `sellerInventory` is false in every committed frontend environment and the gateway inventory route defaults off.

### HELP-SELL-ORDERS-001 and HELP-NOTIFICATIONS-001 — Demo seller commerce

- Routes/components: `business-orders.component.ts`; `business-notification-center.component.ts`; buyer `notification-center.component.ts`; seller layout conditional navigation.
- Client/API: `business-order.service.ts`; `notification.service.ts`; Order `BusinessOrderController.java` and `BusinessOrderReturnController.java`; Notification `NotificationController.java` and `BusinessNotificationController.java`.
- Flags/runtime: business orders, fulfillment, returns, notifications, Order notification outbox, Notification read API/commerce events in `environment.demo-checkout.ts` and `docker-compose.cart-runtime.yml`.
- Verified behavior: accept, process, manual demo shipment, simulated delivery, authorize/receive return, inventory disposition, and in-app read state. No email/SMS/push/WebSocket delivery exists.

### HELP-SAFETY-001 — Reports and appeals

- Routes/components: `report-dialog.component.ts`; listing/store report launchers; `account-appeals.component.ts`.
- Client/API: `report.service.ts`; `appeal.service.ts`; Auth `reporting/ReportController.java`; Auth `appeals/AppealController.java`; Product/Auth safe target context clients.
- Verified behavior: current UI reports `LISTING` and `BUSINESS`; `OTHER` requires explanation; report submission returns a support reference; reports do not auto-enforce; eligible affected actors can appeal; appeals do not pause enforcement.
- Endpoint ambiguity resolved: Product also contains an older `ListingReportController` on `/api/v1/reports`, but its intake is default-off. The current UI and gateway route use the Auth-owned report contract and response envelope.

### HELP-SUPPORT-001 — Support

- Routes/components: `support-ticket-list.component.ts`; `support-ticket-new.component.ts`; `support-ticket-detail.component.ts`.
- Client/API: `support.service.ts`; Auth `support/SupportTicketController.java`; `SupportService.java`.
- Verified behavior: authenticated creation/list/detail/reply, validated optional order/business/listing references, minimum subject/description lengths, no reply form after resolution.

### HELP-AI-001 — Optional assistant

- Routes/components: Agent route factories in `app.routes.ts`; capability tokens in `agent-customer-service.capability.ts`; `conversation-shell.component.ts`; `agent-marketplace-discovery.component.ts`; `agent-marketplace-v2-page.component.ts`; listing-detail launcher; optional listing proposal review in the listing form.
- Client/API: Agent frontend services; gateway Agent conditional routes; `agent-service/src/msb_agent_service/config.py`, `api.py`, `discovery_api.py`, and `marketplace_agent_v2/` runtime/tool allowlist.
- Flags/runtime: AI environment variants, `docker-compose.demo-ai-main.yml`, and default-off Agent switches in `docker-compose.demo.yml`.
- Verified behavior: optional/default-off, authoritative listing links, explicit stop/retry semantics, and no authorization/confirmation bypass.

## Missing, hidden, or unclear customer workflows

1. The global header placeholder says **Search listings and stores**, but submission navigates to `/marketplace`, whose results are individual listings only. Business search is a separate `/stores` form. Help documents actual behavior; UI copy remains potentially misleading.
2. Production Angular enables the cart while gateway cart defaults off. A misaligned deployment can show a cart that cannot load. This is a rollout/configuration gap, not a customer workflow.
3. No committed build exposes seller inventory. Do not publish inventory instructions until a frontend build and matching gateway/service activation exist.
4. There is no real payment provider, real refund, carrier integration, label purchase, tax service, or shipment guarantee. Current checkout, delivery, cancellation refund, and return refund behavior is local demo only.
5. There is no customer report history/detail page. The post-submit support reference is the only customer-visible report receipt.
6. The report model supports `USER`, but no current public user-profile screen exposes **Report user**.
7. There is no password reset/change or email-change UI.
8. **View trade history** is disabled, ratings display **Not rated**, and customer reviews are not implemented. Completed individual sales are represented only through conversation-gated completion and the seller's completed-sales count.
9. Marketplace chat is text-only. Attachments, blocking, realtime transport guarantees, business-order chat, and structured offers are absent.
10. Support categories include payment and refund even when commerce is unavailable. Selecting a category creates a support ticket only; it does not create the named financial action.
11. Direct dispute routes exist even when the order list is feature-gated. They are not a general-purpose complaint path and need an eligible business order group.
12. AI availability requires multiple aligned switches. A frontend entry point alone is not release evidence. The AI demo can retrieve approved public articles through the read-only `retrieve_help` tool, but that does not prove any separately gated commerce capability is enabled.

## Public-corpus exclusions

- Do not ingest this internal coverage file into customer retrieval.
- Do not publish admin routes, admin permissions, internal service routes, environment secrets, local credentials, test identities, or private account data.
- The public articles contain no admin procedures, secrets, test credentials, or private account information.

## Relevant approved requirement and roadmap IDs

The current implementation corresponds to completed or explicitly activated slices including IAM-02 through IAM-06, IND-01/02, LIST-01 through LIST-08, SEARCH-01/01A/01B/03/05, CHAT-01 through CHAT-07, BUS-01 through BUS-05, BUS-LIST-01 through BUS-LIST-06, ADM-REP-01, ADM-APL-00/01/02, ADM-SUP-00/01, and separately gated V2 cart/checkout/order/notification/return slices. These IDs are traceability only; live routes, flags, UI, and backend behavior above remain the availability evidence.
