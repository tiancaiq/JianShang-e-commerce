# Product Development Roadmap

## 1. Release Scope

### MVP

- Foundation
- Authentication and user accounts
- Individual seller profile
- Business seller profile and basic store profile
- Listings and media
- Guest browsing, search, listing detail, and public storefront
- Basic buyer/seller chat
- Basic admin moderation for businesses and listings

### V2

- Business cart
- Inventory
- Checkout and payment
- Orders and shipping
- Notifications

### V3

- Reviews and reputation
- Advanced admin and trust operations
- AI assistant
- Advanced analytics

Individual trade completion verification and public completed-sales count are
retained as approved product design, but implementation is deferred until the
basic MVP chat and listing experience has been validated.

## 2. Current Work Boundary

Phase 0 and Phase 1 are complete. MVP feature implementation has started.

The current repository contains implementation work for:

- Authentication and accounts: IAM-00 through IAM-06
- Login/session UX stabilization: LOGIN-01 complete
- Sign-up and external identity provider planning: SIGNUP-00 complete
- Keycloak self-registration: SIGNUP-01 complete
- Marketplace auth dialog and popup OIDC: SIGNUP-02 complete
- Marketplace native auth: SIGNUP-03 complete
- Backend password login bridge: LOGIN-02 complete
- Backend credential registration bridge: SIGNUP-04 complete
- Google identity provider wiring: SIGNUP-05 complete
- User profile roadmap: USER-00 complete as a planning slice
- Individual seller profile: IND-01 and IND-02
- Business onboarding/admin decision: BUS-01 through BUS-04
- Business store item self-publishing: BUS-LIST-00 planned and BUS-LIST-01
  through BUS-LIST-03 complete
- Listing foundation, drafts, media, submit, admin decision, public detail, and
  planned engagement metrics: LIST-00 through LIST-08
- Public UI surface split: SITE-01
- Search and storefront: SEARCH-00, SEARCH-01, SEARCH-01A, SEARCH-01B,
  SEARCH-03, and SEARCH-04
- Local/demo deployment support for teammate review

The three product surfaces are:

- Public user marketplace site: guests can browse goods without login; signed
  in users can manage profile, sell individual items, and chat.
- Business seller portal: approved merchants manage store profile and basic
  business listings in MVP.
- Admin portal: platform staff manage business approval and listing
  moderation in MVP.

Individual sellers use the public marketplace/account experience for personal
listing management. They do not use the business seller portal.

UI direction:

- Marketplace uses a shopping/commerce style with prominent search,
  categories, listing cards, listing detail pages, and clear seller type
  labels.
- Business seller portal uses a management dashboard style for onboarding,
  store profile, and listing-management work.
- Admin portal uses a management dashboard style for review queues,
  moderation decisions, and audit context.
- Do not spend MVP time making seller/admin look like the public marketplace.
  Their layouts should support operational work.

Detailed marketplace UI redesign direction:

- `docs/mvp/ui/marketplace-ui-redesign.md`

Before starting another product feature slice, restore the verification
baseline after the demo deployment:

- Frontend tests should pass.
- Backend tests should pass in an environment where Testcontainers can access
  Docker.
- Demo documentation should be intentionally tracked or removed.
- Any changed CI/deployment assumptions should be documented.

Stabilization sprint:

- Before continuing feature development, complete the P0 cleanup slices in
  `docs/mvp/fix/stabilization/general/fix-02-stabilization-sprint-plan.md`.
- Before continuing user profile, chat, likes, reviews, or other user
  communication work, complete the P0 auth/login cleanup slices in
  `docs/mvp/fix/stabilization/auth-login/fix-03-auth-login-stabilization-sprint.md`.
- Before starting `SEARCH-01A`, complete the P0 pre-search cleanup slices in
  `docs/mvp/fix/stabilization/pre-search/fix-04-pre-search-stabilization-sprint.md`.
- Before starting chat or another user-to-user feature, complete the P0
  post-search/profile cleanup slices in
  `docs/mvp/fix/stabilization/post-search/fix-05-post-search-profile-stabilization-sprint.md`.

Do not start V2 or V3 features until the MVP browser, seller, listing, search,
chat, and moderation paths are validated.

Business inventory, cart, checkout, payment, orders, shipping, and
notifications remain V2 even though the seller portal exists in MVP.
Business store item publishing may be implemented for MVP public browsing, but
it must not create payment transactions, carts, checkout sessions, inventory
reservations, orders, shipping, fulfillment, or notifications.

## 3. Phase 0: Documentation Baseline

Status: complete.

Phase 0 established the release boundaries and initial MVP documentation.

Reference docs:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`

## 4. Phase 1: Engineering Setup

Status: complete.

Phase 1 created only the development foundation required for later features.

Completed setup slices:

| Slice | Status | Reference |
| --- | --- | --- |
| P1-01 Verify local toolchain | Complete | `docs/phase1-baseline.md` |
| P1-02 Establish backend build baseline | Complete | `docs/phase1-baseline.md` |
| P1-03 Establish frontend build baseline | Complete | `docs/phase1-baseline.md` |
| P1-04 Decide one authentication architecture | Complete | `docs/adr/0001-keycloak-oidc-bff-authentication.md` |
| P1-05 Define MySQL ownership and migration conventions | Complete | `docs/adr/0002-mysql-database-ownership-and-migration-conventions.md` |
| P1-06 Create minimal backend shared modules | Complete | `common-core`, `common-web`, `common-testing` |
| P1-07 Standardize API error and correlation contracts | Complete | `docs/p1-07-common-web-reference.md` |
| P1-08 Define runtime configuration rules | Complete | `docs/p1-08-runtime-configuration.md` |
| P1-09 Prepare Angular workspace structure | Complete | `docs/p1-09-frontend-workspace-plan.md` |
| P1-10 Add CI baseline | Complete | `docs/p1-10-ci-baseline.md` |
| P1-11 Add code quality and architecture checks | Complete | `docs/p1-11-architecture-guardrails.md` |
| P1-12 Produce Phase 1 verification report | Complete | `docs/phase1-verification-report.md` |

Phase 1 completion criteria:

- Backend and frontend baselines are reproducible.
- Authentication and database conventions are decided.
- Minimal shared technical modules build.
- Error and correlation contracts exist.
- CI baseline exists.
- Architecture guardrails exist.

## 5. MVP Feature Progress

### 5.1 Authentication And Accounts

Status: implemented through profile view/edit.

Reference plan:

- `docs/mvp/iam/core/iam-authentication-accounts-implementation-plan.md`

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| IAM-00 Legacy auth cleanup plan | Complete | `docs/mvp/iam/core/iam-00-legacy-auth-cleanup-plan.md` |
| IAM-01 Keycloak local setup | Complete | `docs/mvp/iam/core/iam-01-keycloak-local-setup.md` |
| IAM-02 Gateway BFF login/logout/session | Complete | `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md` |
| IAM-03 Identity user table and Keycloak `sub` mapping | Complete | `docs/mvp/iam/core/iam-03-identity-user-keycloak-sub-mapping.md` |
| IAM-04 Frontend auth session awareness | Complete | `docs/mvp/iam/core/iam-04-frontend-auth-session-awareness.md` |
| IAM-05 Protected route test | Complete | `docs/mvp/iam/core/iam-05-protected-route-test.md` |
| IAM-06 Profile view/edit | Complete | `docs/mvp/iam/core/iam-06-profile-view-edit.md` |

Login/session stabilization:

| Slice | Status | Reference |
| --- | --- | --- |
| LOGIN-01 Login and session UX | Complete | `docs/mvp/iam/login/login-01-login-session-ux-plan.md` |
| LOGIN-02 Backend password login bridge | Complete | `docs/mvp/iam/login/login-02-backend-password-login-bridge.md` |

Sign-up and external identity providers:

| Slice | Status | Reference |
| --- | --- | --- |
| SIGNUP-00 Sign-up and external identity provider plan | Complete | `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md` |
| SIGNUP-01 Keycloak self-registration | Complete | `docs/mvp/iam/signup/signup-01-keycloak-self-registration.md` |
| SIGNUP-02 Marketplace auth dialog and popup OIDC | Complete | `docs/mvp/iam/signup/signup-02-marketplace-auth-dialog-popup.md` |
| SIGNUP-03 Marketplace native auth | Complete | `docs/mvp/iam/signup/signup-03-marketplace-native-auth.md` |
| SIGNUP-04 Backend credential registration bridge | Complete | `docs/mvp/iam/signup/signup-04-backend-credential-registration-bridge.md` |
| SIGNUP-05 Google identity provider wiring | Complete | `docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md` |

Remaining account work before MVP completion:

- User profile roadmap:
  - `docs/mvp/iam/user-profile/user-00-user-profile-roadmap.md`
- Keep the marketplace, seller portal, and admin login/session/logout
  experience verified as future protected flows are added.
- Keep registration/password recovery lifecycle owned by Keycloak.
- Do not reintroduce browser token storage or custom JWT issuance.

User profile and account experience:

| Slice | Status | Reference |
| --- | --- | --- |
| USER-00 User profile roadmap | Planned | `docs/mvp/iam/user-profile/user-00-user-profile-roadmap.md` |
| USER-01 Marketplace account profile consolidation | Complete | `docs/mvp/iam/user-profile/user-01-marketplace-account-profile-consolidation.md` |
| USER-02 Safe public identity labels | Complete | `docs/mvp/iam/user-profile/user-02-safe-public-identity-labels.md` |
| USER-03 Account dashboard shell | Complete | `docs/mvp/iam/user-profile/user-03-account-dashboard-shell.md` |
| USER-04 Marketplace profile card shell | Complete | `docs/mvp/iam/user-profile/user-04-marketplace-profile-card-shell.md` |
| USER-05 Avatar image upload | Complete | `docs/mvp/iam/user-profile/user-05-avatar-image-upload.md` |
| USER-STAB-01 Profile/avatar stabilization | Complete | `docs/mvp/iam/user-profile/user-stab-01-profile-avatar-stabilization.md` |
| USER-06 Chat identity display support | Planned | `docs/mvp/iam/user-profile/user-06-chat-identity-display-support.md` |

### 5.2 Individual Seller Profile

Status: implemented for activation and current profile.

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| IND-01 Individual seller activation | Complete | `docs/mvp/ind/ind-01-individual-seller-activation.md` |
| IND-02 Current individual seller profile | Complete | `docs/mvp/ind/ind-02-current-individual-seller-profile.md` |
| IND-03 Marketplace individual selling plan | Planned | `docs/mvp/ind/ind-03-marketplace-individual-selling-plan.md` |

Remaining MVP work:

- Use the active individual seller profile when creating and managing
  individual listings inside the marketplace account experience.
- Keep completed-sales count deferred until the V3 trade completion flow.

### 5.3 Business Seller Profile And Basic Store

Status: business application, approval path, and basic store profile
management are implemented.

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| BUS-01 Business application draft | Complete | `docs/mvp/bus/bus-01-business-application-draft.md` |
| BUS-02 Submit business application | Complete | `docs/mvp/bus/bus-02-submit-business-application.md` |
| BUS-03/BUS-04 Verification callback and admin decision | Complete | `docs/mvp/bus/bus-03-04-business-verification-and-admin-decision.md` |
| BUS-05 Basic store profile management | Complete | `docs/mvp/bus/bus-05-basic-store-profile-management.md` |
| BUS-LIST-00 Store item self-publishing plan | Planned | `docs/mvp/bus/bus-list-00-store-item-self-publishing-plan.md` |
| BUS-LIST-01 Current business/store context | Complete | `docs/mvp/bus/bus-list-01-current-business-store-context.md` |
| BUS-LIST-02 Store item draft and edit | Complete | `docs/mvp/bus/bus-list-02-store-item-draft-edit.md` |
| BUS-LIST-03 Store item media | Complete | `docs/mvp/bus/bus-list-03-store-item-media.md` |

Remaining MVP work:

- Store item self-publishing to `/stores` and reactive admin removal after
  store item draft/edit/media. Store items are business self-published to
  `/stores` without item-level admin approval.
- Stronger admin review queue UX beyond entering an application ID manually.
- Inventory, orders, fulfillment, and payment management remain V2.

### 5.4 Listings And Media

Status: listing schema, categories, draft creation, media metadata request and
confirm, ordered draft image attachment, draft editing, submission for review,
admin listing moderation decisions, public approved listing detail, public
browse, object-storage-backed image delivery, and account-scoped listing
visits/likes are implemented.

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| LIST-00 Listing domain foundation | Complete | `docs/mvp/list/list-00-listing-domain-foundation.md` |
| LIST-01 Create listing draft | Complete | `docs/mvp/list/list-01-create-listing-draft.md` |
| LIST-02 Media upload request and confirm metadata | Complete | `docs/mvp/list/list-02-media-upload-request-confirm.md` |
| LIST-03 Attach and order listing images | Complete | `docs/mvp/list/list-03-attach-and-order-listing-images.md` |
| LIST-04 Edit listing draft | Complete | `docs/mvp/list/list-04-edit-listing-draft.md` |
| LIST-05 Submit listing for moderation | Complete | `docs/mvp/list/list-05-submit-listing-for-moderation.md` |
| LIST-06 Admin listing moderation decision | Complete | `docs/mvp/list/list-06-admin-listing-moderation-decision.md` |
| LIST-07 Public listing detail | Complete | `docs/mvp/list/list-07-public-listing-detail.md` |
| MEDIA-01 Object storage image delivery | Complete | `docs/mvp/list/media-01-object-storage-image-delivery.md` |
| LIST-FE Stabilize listing frontend behavior | Complete | `docs/mvp/list/list-frontend-stabilization.md` |
| LIST-08 Listing engagement visits and likes | Complete | `docs/mvp/list/list-08-listing-engagement-visits-likes.md` |

Do not implement platform checkout, inventory reservation, or business orders
as part of listing work.

### 5.5 Search And Storefront

Status: split marketplace/storefront search, shared cursor pagination, and the
OpenSearch-derived projection are implemented. The public UI is split into
individual marketplace and business store surfaces.

MVP goal:

- Guests can visit the public site and see approved goods without logging in.
- The public marketplace should feel like a shopping site rather than an
  admin dashboard.
- Listing and storefront pages clearly distinguish individual sellers from
  business sellers.
- Individual listings show that payment and delivery are arranged
  off-platform.
- Individual marketplace search and business storefront browse are separate
  public experiences. They may reuse listing tables and response primitives,
  but should not share checkout language, calls to action, or page layout.
- Planned business store item publishing changes the business storefront path:
  `/stores` should show active business self-published store items without
  requiring item-level admin approval. Payment transactions remain V2.

Recommended slices:

0. SITE-01 public UI surface split.
   - Status: complete.
   - Reference: `docs/mvp/site/site-01-public-ui-surface-split.md`
1. SEARCH-00 search/storefront read model plan.
   - Status: complete.
   - Reference: `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
   - Split public contracts are implemented:
     `/api/v1/public/marketplace/listings/search` for individual marketplace
     listings and `/api/v1/public/stores/listings/search` for business
     storefront listings.
2. SEARCH-01 public approved listing browse without OpenSearch.
   - Status: complete.
   - Reference: `docs/mvp/search/search-01-public-approved-listing-browse.md`
3. SEARCH-01A individual marketplace search.
   - Status: complete.
   - Reference:
     `docs/mvp/search/search-02a-individual-marketplace-keyword-search.md`
   - Scope: keyword, category, condition, price, city/county, and sort for
     approved active `INDIVIDUAL` listings in the marketplace user site.
   - Excludes business storefronts, cart, checkout, and structured offers.
4. SEARCH-01B business storefront search.
   - Status: complete.
   - Reference:
     `docs/mvp/search/search-01b-business-storefront-search.md`
   - Scope: keyword, category, condition, price, city/county, and sort for
     approved active `BUSINESS` listings in the public stores surface.
   - Excludes inventory reservation, cart, checkout, payment, orders, and
     shipping.
5. SEARCH-03 shared filters, sorting, and cursor pagination.
   - Status: complete.
   - Reference:
     `docs/mvp/search/search-03-shared-cursor-pagination.md`
   - Scope: category, seller type, condition, price, location, stable sort, and
     cursor pagination for the database-backed public read paths.
6. SEARCH-04 OpenSearch projection only after the database-backed read path is
   correct.
   - Status: complete.
   - Reference:
     `docs/mvp/search/search-04-opensearch-projection.md`

### 5.6 Basic Buyer/Seller Chat

Status: CHAT-00 through CHAT-06 and CHAT-STAB-P0-01 complete.

Recommended slices:

1. CHAT-00 chat domain and authorization plan.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-00-chat-domain-plan.md`
2. CHAT-01 start listing conversation.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-01-start-listing-conversation.md`
3. CHAT-02 send/read text messages.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-02-send-read-text-messages.md`
4. CHAT-03 conversation list and unread state.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-03-conversation-inbox-read-state.md`
5. CHAT-04 floating marketplace chat launcher.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-04-floating-marketplace-chat-launcher.md`
6. CHAT-05 conversation-gated trade completion.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-05-conversation-gated-trade-completion.md`
   - Note: this is an explicit minimal scope expansion from the earlier V3
     trade-completion boundary. It does not add payments, shipping, reviews,
     structured offers, buyer contact sharing, or completed-sales reputation.
7. CHAT-06 transaction done flow verification.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-06-transaction-done-flow-verification.md`
   - Scope: end-to-end seller mark-done, buyer confirm, listing close/search
     removal, seller history visibility, authorization checks, and sold
     quantity on the completion proof.
8. CHAT-STAB-P0-01 chat verification and contract audit.
   - Status: complete.
   - Reference: `docs/mvp/chat/chat-stab-p0-01-chat-verification-and-contract-audit.md`
9. MVP-READINESS-01 public marketplace smoke pass.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/mvp-readiness-01-public-marketplace-smoke-pass.md`
   - Scope: automated frontend/backend readiness pass for guest marketplace,
     stores, listing detail, account routes, and chat entry points.

MVP chat uses a dedicated `chat-service` for reusable conversation mechanics,
but only enables `LISTING_BUYER_SELLER` conversations. Customer service,
business-admin support, admin direct messaging, AI agent sessions, reviews,
reports, blocking, and realtime delivery are future slices.

Structured individual trade creation, reputation increments, reviews, and
advanced completion disputes remain V3. The MVP exception is the minimal
conversation-gated listing close covered by CHAT-05 and CHAT-06.

### 5.7 Basic Admin Moderation

Status: ADM-00 admin shell, ADM-BUS-01 business application queue, and
ADM-BUS-02 business application detail are complete; ADM-BUS-03 decision UX,
business application admin decision, and basic listing moderation decision are
also complete.

Reference: `docs/mvp/adm/admin-mvp-plan.md`

Recommended slices:

1. ADM-00 admin portal shell hardening.
2. ADM-BUS-01 business application queue.
3. ADM-BUS-02 business application review detail.
4. ADM-BUS-03 business application decision UX.
5. ADM-LIST-00 moderation case foundation for richer listing review queues.
6. ADM-LIST-01 listing submission creates a moderation case.
7. ADM-LIST-02 listing moderation queue upgrade with claim/release.
8. ADM-LIST-03 listing review detail and case resolution.
9. ADM-LIST-04 active listing admin edit and removal.
10. ADM-LIST-05 listing review search.
11. ADM-AUD-01 minimal admin audit visibility for MVP workflows.

Reports, starting with ADM-REP-01 user-reported listing queue,
suspensions/restores, support cases, chat evidence review, payment/order/finance
operations, advanced trust/disputes, and AI moderation assistance are admin
roadmap scope but deferred until after the MVP admin foundation is stable.

## 6. Immediate Next Work

### FIX-02 Stabilization Sprint

Status: planned.

Reference:

- `docs/mvp/fix/stabilization/general/fix-02-stabilization-sprint-plan.md`

Goal:

- Clean up completed MVP work before more teammates start coding.
- Do not add features.
- Fix route/product-surface drift, legacy auth leftovers, V2 demo leakage,
  duplicate docs, and baseline verification gaps.

P0 cleanup slices:

1. STAB-P0-01 Stabilize Git state and verification baseline.
   - Status: complete.
   - Reference: `docs/mvp/fix/stabilization/general/stab-p0-01-git-verification-baseline.md`
2. STAB-P0-02 Remove individual seller tools from business seller portal.
   - Status: complete.
   - Reference: `docs/mvp/fix/stabilization/general/stab-p0-02-business-seller-portal-boundary.md`
3. STAB-P0-03 Remove or quarantine V2 demo UI from active MVP navigation.
   - Status: complete.
   - Reference: `docs/mvp/fix/stabilization/general/stab-p0-03-v2-demo-ui-quarantine.md`
4. STAB-P0-04 Remove legacy custom JWT issuance path.
   - Status: complete.
   - Reference: `docs/mvp/fix/stabilization/general/stab-p0-04-legacy-jwt-cleanup.md`
5. STAB-P0-05 Fix documentation duplicates and slice naming drift.

Completed backend cleanup:

- STAB-P2-06 Active backend migration boundary.
  - Status: complete.
  - Reference:
    `docs/mvp/fix/stabilization/general/stab-p2-06-active-backend-migration-boundary.md`

### FIX-03 Auth/Login Stabilization Sprint

Status: planned.

Reference:

- `docs/mvp/fix/stabilization/auth-login/fix-03-auth-login-stabilization-sprint.md`

Goal:

- Review completed login, logout, sign-up, native auth, and external provider
  wiring before building more user profile or communication features.
- Do not add features.
- Preserve API contracts and database schema.

P0 cleanup slices:

1. AUTH-STAB-P0-01 Auth verification baseline.
   - Status: complete.
   - Reference: `docs/mvp/fix/stabilization/auth-login/auth-stab-p0-01-auth-verification-baseline.md`.
2. AUTH-STAB-P0-02 Align Google provider docs with hidden CTA.
   - Status: complete.
3. AUTH-STAB-P0-03 Native auth session and CSRF regression coverage.
   - Status: complete.
4. AUTH-STAB-P0-04 Logout and return-url regression coverage.
   - Status: complete.

### FIX-04 Pre-Search Stabilization Sprint

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/pre-search/fix-04-pre-search-stabilization-sprint.md`

Goal:

- Review completed MVP auth, seller, business, listing, media, public browse,
  and public UI split work before adding search behavior.
- Do not add features.
- Preserve API contracts.
- Preserve database schema unless a verified migration or data-safety bug
  requires a forward-safe migration.
- Keep each cleanup slice scoped to one pull request.

P0 cleanup slices:

1. PRESEARCH-STAB-P0-01 Verification and git baseline refresh.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-01-verification-git-baseline-refresh.md`
2. PRESEARCH-STAB-P0-02 Public surface contract regression audit.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-02-public-surface-contract-regression-audit.md`
3. PRESEARCH-STAB-P0-03 Media storage secret and environment hygiene.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-03-media-storage-secret-environment-hygiene.md`
4. PRESEARCH-STAB-P0-04 Source-of-truth documentation cleanup.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-04-source-of-truth-documentation-cleanup.md`

P1 cleanup themes:

- Listing frontend component responsibility cleanup.
- Listing lifecycle regression test cleanup.
- Public listing DTO and client naming cleanup.
- Backend listing service readability cleanup.

P2 cleanup themes:

- Marketplace CSS budget cleanup.
- Manual browser smoke checklist.
- Small shared UI primitive consolidation.
- Historical demo and deferred-service index.

### FIX-01 Restore Verification Baseline

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/fix-01-restore-verification-baseline.md`

Goal:

- Bring local verification back to green after demo deployment and same-origin
  BFF URL changes.

Tasks:

- Update stale frontend tests that still expect absolute
  `http://localhost:9000/api/v1/...` URLs when runtime code now uses
  same-origin `/api/v1/...`.
- Confirm `npm.cmd test -- --watch=false` passes.
- Confirm `npm.cmd run build` passes.
- Run backend tests from an environment where Testcontainers can access
  Docker.
- Decide whether `docs/deploy/team-demo-readme.md` is intentionally tracked.
- Document any remaining demo-only deployment limitations.

Acceptance criteria:

- Frontend tests pass.
- Frontend build passes.
- Backend tests pass or any local Docker/Testcontainers limitation is
  documented with a repeatable command that passes in CI/normal shell.
- No new product feature behavior is added.

### FIX-05 Post-Search And Profile Stabilization Sprint

Status: in progress.

Reference:

- `docs/mvp/fix/stabilization/post-search/fix-05-post-search-profile-stabilization-sprint.md`

Goal:

- Stabilize completed profile/avatar, split search, cursor pagination, and
  OpenSearch projection work before chat or more user-to-user features.
- Do not add features.
- Preserve API contracts and database schema unless a verified data-safety
  issue requires a forward-safe migration.

P0 cleanup slices:

1. POSTSEARCH-STAB-P0-01 Verification and git baseline refresh.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-01-verification-git-baseline-refresh.md`
2. POSTSEARCH-STAB-P0-02 Auth, session, avatar, and storage security audit.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-02-auth-session-avatar-storage-security-audit.md`
3. POSTSEARCH-STAB-P0-03 Public search and storefront contract audit.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-03-public-search-storefront-contract-audit.md`
4. POSTSEARCH-STAB-P0-04 Source-of-truth documentation and roadmap cleanup.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-04-source-of-truth-documentation-roadmap-cleanup.md`
5. POSTSEARCH-STAB-P0-05 OpenSearch projection operational safety.
   - Status: planned.

### Next Implementation Sequence

1. FIX-01: Restore verification baseline.
   - Status: complete.
   - Decide whether to keep `docs/deploy/team-demo-readme.md`.
   - Run backend tests with Docker/Testcontainers available.
   - Confirm frontend tests and build stay green.
   - Do not add product behavior.

2. LIST-02: Media upload request and confirm metadata.
   - Status: complete.
   - Add media metadata persistence.
   - Add upload-request API.
   - Add confirm-upload API.
   - Keep media scoped to the owning seller or business.
   - No publish or search behavior yet.

3. LIST-03: Attach and order listing images.
   - Status: complete.
   - Attach confirmed media to draft listings.
   - Support display order.
   - Support alt text.
   - Enforce listing ownership.
   - Add an image section to the draft listing frontend.

4. SITE-00: Logical three-site separation.
   - Status: complete.
   - Reference: `docs/mvp/site/site-00-logical-three-site-separation.md`
   - Keep one Angular app.
   - Separate `/`, `/seller`, and `/admin` route groups.
   - Add marketplace, seller, and admin layouts.
   - Keep marketplace guest-accessible.
   - Protect seller and admin routes.
   - Do not add inventory, orders, or payments.

5. LIST-04: Edit listing draft.
   - Status: complete.
   - Allow the owner to edit draft fields.
   - Use optimistic locking with `If-Match`.
   - Keep the listing status as draft.
   - Prevent editing owner, status, and moderation fields.

6. LIST-05: Submit listing for moderation.
   - Status: complete.
   - Submit a draft listing for review.
   - Require required fields and media.
   - Move the listing to pending review.
   - Prevent further draft edits unless changes are requested.

7. LIST-06: Admin listing moderation decision.
   - Status: complete.
   - Admin can approve, reject, or request changes.
   - Record reviewer, reason, and decision timestamp.
   - Approved listings become eligible for public read paths.
   - Add seller-facing moderation status.

8. LIST-07: Public listing detail.
   - Status: complete.
   - Add a guest-readable listing detail endpoint.
   - Show only approved active listings.
   - Hide draft, rejected, and private seller data.
   - Clearly label individual versus business sellers.
   - Show off-platform payment and delivery notice for individual listings.

9. SEARCH-00: Search and storefront read model plan.
   - Status: complete.
   - Reference: `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
   - Decide the initial database-backed read path.
   - Define public listing cards.
   - Define storefront shape.
   - Implement split public contracts for individual marketplace listings and
     business storefront listings.
   - Defer OpenSearch until database-backed browse is correct.

10. SEARCH-01: Public approved listing browse.
   - Status: complete.
   - Let guests browse approved listings.
   - Show newest approved active listings on the marketplace homepage.
   - Do not add OpenSearch yet.

11. FIX-04: Pre-search stabilization sprint.
    - Status: complete.
    - Reference:
      `docs/mvp/fix/stabilization/pre-search/fix-04-pre-search-stabilization-sprint.md`
    - Completed P0, P1, and P2 cleanup slices before search implementation.
    - Do not add features.
    - Preserve API contracts and database schema.

12. SEARCH-01A: Individual marketplace search.
    - Status: complete.
    - Reference:
      `docs/mvp/search/search-02a-individual-marketplace-keyword-search.md`
    - Search approved active individual listings from the public marketplace.
    - Use backend keyword, category, condition, price, city/county, and sort
      handling instead of browser-only filtering.
    - Keep off-platform trade disclosure and no checkout language.

13. SEARCH-01B: Business storefront search.
    - Status: complete.
    - Reference:
      `docs/mvp/search/search-01b-business-storefront-search.md`
    - Let guests search approved active business listings.
    - Hide individual listings, suspended/private data, and business internals.
    - Do not add cart, inventory, checkout, payment, orders, or shipping.

14. SEARCH-03: Shared filters, sorting, and cursor pagination.
    - Status: complete.
    - Reference:
      `docs/mvp/search/search-03-shared-cursor-pagination.md`
    - Add cursor pagination and load-more UI.
    - Refine shared filter/sort contracts after both individual and business
      search paths are stable.

15. SEARCH-04: OpenSearch projection.
    - Status: complete.
    - Reference:
      `docs/mvp/search/search-04-opensearch-projection.md`
    - Add a derived OpenSearch projection after the database-backed paged
      search contract is stable.

## 7. Deferred Release Summaries

### V2

Business commerce:

- Cart
- Inventory
- Checkout/payment
- Orders/shipping
- Notifications

### V3

Trust, intelligence, and growth:

- Individual trade completion verification and public completed-sales count
- Reviews/reputation
- Advanced admin/trust operations
- AI assistant
- Advanced analytics
