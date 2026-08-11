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
- Business store item self-publishing and management: BUS-LIST-00 through
  BUS-LIST-06 complete
- Listing foundation, drafts, media, submit, admin decision, public detail, and
  planned engagement metrics: LIST-00 through LIST-08
- Public UI surface split: SITE-01
- Search and storefront: SEARCH-00, SEARCH-01, SEARCH-01A, SEARCH-01B,
  SEARCH-03, SEARCH-04, and SEARCH-05
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
| LOGIN-STAB-03 Multi-surface login/logout stabilization | Complete | `docs/mvp/iam/login/login-stab-03-multi-surface-login-logout.md` |

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
| BUS-LIST-00 Store item self-publishing plan | Complete | `docs/mvp/bus/bus-list-00-store-item-self-publishing-plan.md` |
| BUS-LIST-01 Current business/store context | Complete | `docs/mvp/bus/bus-list-01-current-business-store-context.md` |
| BUS-LIST-02 Store item draft and edit | Complete | `docs/mvp/bus/bus-list-02-store-item-draft-edit.md` |
| BUS-LIST-03 Store item media | Complete | `docs/mvp/bus/bus-list-03-store-item-media.md` |
| BUS-LIST-04 Self-publish to `/stores` | Complete | `docs/mvp/bus/bus-list-04-self-publish-to-stores.md` |
| BUS-LIST-05 Reactive admin removal | Complete | `docs/mvp/bus/bus-list-05-reactive-admin-removal.md` |
| BUS-LIST-06 Business item management list | Complete | `docs/mvp/bus/bus-list-06-business-item-management-list.md` |

Remaining MVP work:

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
individual marketplace and business store surfaces. Search state is URL-based,
and business storefront search revalidates active public business/store
visibility before returning results.

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
- Business store item publishing changes the business storefront path:
  `/stores` shows active business self-published store items without requiring
  item-level admin approval. Payment transactions remain V2.

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
7. SEARCH-05 search state and visibility hardening.
   - Status: complete.
   - Reference:
     `docs/mvp/search/search-05-search-state-and-visibility-hardening.md`
   - Scope: URL-based frontend search state, business store-name/SKU keyword
     matching, active business/store visibility revalidation, and improved
     empty result states.

### 5.6 Basic Buyer/Seller Chat

Status: CHAT-00 through CHAT-07 and CHAT-STAB-P0-01 complete.

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
9. CHAT-07 repeatable trade completion demo hardening.
   - Status: complete.
   - Reference:
     `docs/mvp/chat/chat-07-repeatable-trade-completion-demo-hardening.md`
   - Scope: stable local buyer/seller demo identities, one approved individual
     demo listing, public participant handles, four-stage completion status,
     buyer confirmation review, completed-thread locking, and browser E2E
     coverage for account switching and public listing removal.
10. MVP-READINESS-01 public marketplace smoke pass.
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

### 5.8 V3 AI And Automated Operations Planning

Status: AI-00, AI-CS-01, and AI-RAG-00 planning contracts are complete.
AI-LLM-01 provider foundation is implemented and its replacement runtime
credential passed a live synthetic embedding check. AI-RAG-00 reconciles the
top-level and detailed AI documents with the approved hybrid-RAG direction. AI-RAG-01
implements the vector storage foundation. AI-RAG-02A source publication,
AI-RAG-02B durable Agent Service intake, AI-RAG-02C listing indexing, and
AI-RAG-02D rebuild/deletion operations are implemented. AI-RAG-02C remains
activated against the full local listing backlog. No 02D paid rebuild or
promotion has been run. AI-KNOW-01 Product Service category-guidance ownership
and `AI-RAG-02E-CATEGORY` Agent Service intake, processing, source-complete
rebuild, and scoped retrieval are implemented, verified, and deployed locally
with category flags disabled. Policy, safety, and FAQ sources remain deferred
until their source-owner contracts exist. AI-RAG-03 listing retrieval remains
implemented and verified. `AI-CS-01A` Agent Service persistence is implemented,
verified, migrated locally, and deployed with its independent gate disabled.
`AI-CS-01B` authenticated Agent Service APIs are implemented and verified with
their capability gate disabled. `AI-CS-01C` listing-only hybrid-RAG
orchestration source behavior is implemented and verified offline with mocked
model execution. `AI-CS-01C-2` binds that interface to the existing provider
abstraction and is verified with fake transports only; runtime construction,
external activation, and UI remain disabled and deferred.

Reference:

- `docs/mvp/ai/ai-00-agent-and-automated-operations-plan.md`
- `docs/mvp/ai/ai-rag-00-hybrid-rag-contract-reconciliation.md`
- `docs/mvp/ai/ai-rag-01-opensearch-vector-foundation-plan.md`
- `docs/mvp/ai/ai-rag-02-ingestion-embedding-pipeline-plan.md`
- `docs/mvp/ai/ai-rag-03-listing-knowledge-retriever.md`
- `docs/mvp/ai/ai-know-01-category-guidance-owner-plan.md`
- `docs/mvp/ai/ai-rag-02e-category-guidance-adapter-plan.md`
- `docs/mvp/ai/ai-cs-01-listing-customer-service-assistant.md`
- `docs/mvp/ai/ai-llm-01-openai-provider-foundation.md`
- `docs/mvp/ai/ai-list-01-seller-image-to-listing-proposal.md`
- `docs/mvp/ai/ai-list-02-seller-proposal-review-and-apply-plan.md`
- `docs/mvp/ai/general-ai-agent-implementation-roadmap.md`

AI-00 defines the proposed contract for a listing-bound customer-service
assistant, seller-confirmed image-to-listing content suggestions, and
AI-assisted listing-report classification with narrowly allowlisted reversible
operations. It does not authorize V3 implementation during active MVP work.

Approved slice order:

1. AI-00 agent and automated-operations plan.
   - Status: complete as a documentation-only planning slice.
   - Reference:
     `docs/mvp/ai/ai-00-agent-and-automated-operations-plan.md`
2. AI-LLM-01 OpenAI provider foundation.
   - Status: implemented; a replacement project credential passed the live
     synthetic embedding check on 2026-07-19.
   - Reference:
     `docs/mvp/ai/ai-llm-01-openai-provider-foundation.md`
   - Adds the isolated Python/FastAPI runtime, direct Responses API adapter,
     strict text/image/tool smoke paths, safe provider errors, and mocked CI
     verification without exposing a product endpoint.
3. AI-RAG-00 hybrid-RAG knowledge and contract reconciliation.
   - Status: complete as a documentation-only contract slice.
   - Reference:
     `docs/mvp/ai/ai-rag-00-hybrid-rag-contract-reconciliation.md`
   - Reconciles authoritative listing facts with filtered, source-attributed
     OpenSearch knowledge retrieval.
   - Defines source ownership and precedence, chunk metadata and visibility,
     citations and answer actions, staleness and deletion behavior, Flyway
     ownership, and retention/privacy rules.
4. AI-RAG-01 OpenSearch vector foundation.
   - Status: implemented and verified on 2026-07-18.
   - Reference:
     `docs/mvp/ai/ai-rag-01-opensearch-vector-foundation-plan.md`
   - Adds the separate agent-owned vector index family, strict mapping,
     validated embedding identity/dimensions, atomic read/write aliases,
     readiness, safe observability, and real OpenSearch integration tests.
5. AI-RAG-02 ingestion and embedding pipeline.
   - Status: implementation plan complete. `AI-RAG-02A` was implemented and
     verified on 2026-07-18. `AI-RAG-02B` was implemented and verified on
     2026-07-19. `AI-RAG-02C` was implemented and verified on 2026-07-19;
     the full local backlog was activated successfully. `AI-RAG-02D` was
     implemented and deployed on 2026-07-19. `AI-RAG-02E-CATEGORY` was
     implemented, verified, and deployed locally on 2026-07-19 with all
     category flags disabled; the other `02E` sources remain deferred until
     their authoritative source-owner contracts are implemented.
   - Reference:
     `docs/mvp/ai/ai-rag-02-ingestion-embedding-pipeline-plan.md`
   - `AI-RAG-02A` reference:
     `docs/mvp/ai/ai-rag-02a-product-listing-source-publication.md`
   - `AI-RAG-02B` reference:
     `docs/mvp/ai/ai-rag-02b-agent-durable-ingestion-intake.md`
   - `AI-RAG-02C` reference:
     `docs/mvp/ai/ai-rag-02c-listing-embedding-indexing.md`
   - `AI-RAG-02D` reference:
     `docs/mvp/ai/ai-rag-02d-rebuild-deletion-and-promotion.md`
   - Implement in order as `AI-RAG-02A` source publication, `02B` durable
     intake, `02C` listing embeddings/indexing, `02D` rebuild/deletion, and
     `02E` remaining authoritative sources.
6. AI-RAG-03 filtered knowledge retriever.
   - Status: listing-only implementation verified on 2026-07-19.
   - Reference:
     `docs/mvp/ai/ai-rag-03-listing-knowledge-retriever.md`
   - Adds replaceable listing and category-guidance retrieval paths, exact
     current-version and isolation filters, bounded context, strict result
     validation, safe failure behavior, metrics, and real OpenSearch isolation
     tests. Category retrieval remains disabled pending explicit rollout.
7. AI-KNOW-01 Product Service category-guidance owner.
   - Status: implemented, verified, and deployed locally on 2026-07-19.
   - Reference:
     `docs/mvp/ai/ai-know-01-category-guidance-owner-plan.md`
   - Adds human-controlled immutable category-guidance versions, admin
     publication/retirement, exact/export reads, and reference-only outbox
     events. It does not add Agent Service ingestion or generated content.
8. AI-RAG-02E-CATEGORY Agent Service category-guidance adapter.
   - Status: implemented, verified, and locally deployed on 2026-07-19 with
     intake, processing, and retrieval disabled.
   - Reference:
     `docs/mvp/ai/ai-rag-02e-category-guidance-adapter-plan.md`
9. AI-CS-01A agent persistence.
   - Status: implemented, verified, migrated locally, and deployed on
     2026-07-19 with `AGENT_PERSISTENCE_ENABLED=false`.
   - Adds Agent-owned sessions, messages, invocations, tool-call audit,
     database-enforced open-session uniqueness, retry deduplication, actor
     isolation, keyset pagination, and split 90/365-day retention behavior.
10. AI-CS-01B authenticated agent APIs.
    - Status: implemented, verified, and migrated locally on 2026-07-19 with
      `AGENT_CUSTOMER_SERVICE_API_ENABLED=false`.
    - Adds BFF authentication/CSRF routing, Auth Service actor resolution,
      Product Service listing context validation, strict Agent API schemas,
      actor-hidden reads, standard errors/correlation, cursor pagination,
      idempotent replay, Flyway V5 correlation alignment, and disabled-safe
      readiness.
11. AI-CS-01C listing customer-service hybrid-RAG orchestration.
   - Status: source implementation and mocked/offline verification complete on
     2026-07-19. `AI-CS-01C-2` provider answerer adapter binding is also
     source-complete and verified offline; activation remains disabled.
   - Reference:
     `docs/mvp/ai/ai-cs-01-listing-customer-service-assistant.md`
   - Adds deterministic `getListing` and listing-only `retrieveKnowledge`
     execution, trusted actor/listing injection, strict schemas, retry-safe
     hashed tool audits, bounded model context/time/tokens, grounded
     source/action validation, privacy/injection guardrails, safe outage
     behavior, low-cardinality metrics, and offline eval fixtures.
   - `AI-CS-01C-2` adds a strict redacted request schema, the existing
     Responses provider binding, bounded output/time behavior, typed
     rate-limit/timeout/malformed-output mapping, cancellation-safe invocation
     failure, token/latency propagation, hashed safe adapter logs, and
     low-cardinality adapter metrics. It is not constructed by default.
12. AI-CS-01D existing chat UI activation.
    - Status: `AI-CS-01D-A` disabled-by-default gateway/UI source integration
      implemented and verified on 2026-07-20. External activation remains
      disabled pending the evaluation and rollout gate.
      `AI-CS-CLEAN-P0-01` completed the required orchestration/provider/
      gateway/UI cleanup checkpoint on 2026-07-20 and reset the AI lane to
      0/3 without activating the capability.
    - Reuses the existing floating chat and `/account/messages` agent UI while
      keeping agent sessions, DTOs, routes, and persistence outside
      buyer/seller chat.
    - Adds authenticated token relay with browser identity-header stripping,
      strict Angular response validation, reserved agent routes, grounded
      source/action rendering, deterministic listing selection, retry-safe
      outage behavior, seller handoff, and default-hidden/network-silent
      entry points.
13. AI-CS-01E evaluation and rollout.
    - Status: `AI-CS-01E-A` deterministic offline evaluation baseline and
      provisional release-threshold contract implemented and verified on
      2026-07-20. AI lane is 1/3.
    - Adds a strict versioned fixture/report schema and a zero-network runner
      over the real listing retriever/orchestrator boundaries with local
      embedding, OpenSearch-style, and provider fakes.
    - Offline thresholds cover retrieval relevance/recall, annotated-claim
      faithfulness, citation validity/completeness, stale/deleted/cross-listing
      rejection, actor/tool isolation, safe dependency failures, and simulated
      latency. Passing the baseline does not authorize release.
    - Live semantic quality, production latency, pricing/cost, dashboards,
      runtime activation, cohorts, and rollout remain deferred. Capability,
      provider, retrieval, gateway, and frontend release gates remain off.
    - `AI-CS-01E-B` release-gate evaluator and offline observability contract
      implemented and verified on 2026-07-20. AI lane is 2/3.
    - Reference:
      `docs/mvp/ai/ai-cs-01e-release-gate-and-observability-contract.md`
    - Adds strict readiness input/decision/dashboard schemas, report
      freshness/integrity/version enforcement, default-off and kill-switch
      precedence, externally evidenced production approval gates, sequential
      rollout approvals, and fixed rollback triggers. It creates no runtime
      flags, dashboards, cohorts, evidence, or activation.
    - `AI-CS-01E-C` default-off listing context picker and listing-detail
      contextual launch implemented and verified on 2026-07-20. AI lane is
      3/3.
    - Reuses only the approved public individual-listing search/detail
      projections, passes listing ID plus bounded title-safe display context,
      and starts the existing authenticated Agent create flow only after an
      explicit user choice. Default false capability behavior remains hidden
      and network-silent.
    - `AI-CS-CLEAN-P0-02` evaluation/release-gate/listing-context cleanup
      completed and verified on 2026-07-20. It tightened fixed-schema
      consistency, kept offline evidence distinct from unknown production
      latency/cost, consolidated ID/title-only listing context, and preserved
      default-off network silence and no automatic action. AI lane reset from
      3/3 to 0/3; pause before `AI-LC-01` or rollout pending a separate PM
      assignment.
14. AI-LC-01 LangChain integration after the measurable baseline RAG path.
    - `AI-LC-01A` stopped cleanly on 2026-07-20 because `langchain` and
      `langchain-core` were absent from the Agent environment, declaration,
      lock state, and local cache. No dependency or source change was made;
      AI lane remained 0/3.
    - A later explicit Python approval completed `AI-CS-02B` with pinned
      `langchain-core==1.4.9` only. Typed Runnables remain behind the existing
      Agent answerer/retrieval/provider interfaces, with no LangChain types in
      APIs or persistence and no LangGraph, memory, or checkpointer.
15. AI-LIST-01 seller image-to-listing content proposal.
    - `AI-LIST-01A` default-off proposal contract and offline orchestration
      implemented and verified on 2026-07-20. The Agent-owned strict schema,
      actor-scoped owned-draft media protocol, injected fake vision transport,
      privacy/injection guardrails, idempotent replay, safe audits/metrics, and
      offline evaluation tests perform no Product write or runtime activation.
    - `AI-LIST-01B` offline multimodal provider adapter binding implemented and
      verified on 2026-07-20. It reuses the existing typed Responses operation
      with separated trusted instructions/untrusted images, strict provider
      context/output/deadline limits, no tools, `store=false`, safe error and
      cancellation mapping, replay/audit/metric safety, and exact 01A baseline
      parity. It remains default-off and unwired.
    - `AI-LIST-01C` authorized Product listing-media tool adapter implemented
      and verified on 2026-07-20. Product now owns a service-authenticated,
      default-disabled read contract that rechecks individual listing
      ownership/editable-draft state and selected media state, verifies actual
      JPEG/PNG/WebP bytes/size/hash, and returns no storage URL/key or seller
      data. The Agent HTTP adapter is independently default-off/unwired,
      redirect-free, bounded, cancellation-safe, and strictly revalidates the
      response. Focused Product unit/API/MySQL and Agent fake-HTTP suites pass
      with no external request or write. AI lane reached 3/3 and triggered the
      mandatory AI-LIST cleanup recorded below.
    - `AI-LIST-CLEAN-P0-01` listing proposal and media boundary cleanup
      completed and verified on 2026-07-20. It rejects contradictory
      suggestion/unknown output, centralizes image magic checks, audits
      media-tool cancellation with retry-safe replay cleanup, and ends the
      Product authorization transaction before storage reads. Default-off,
      no-call, ownership/state, privacy, proposal-only, and release-blocked
      behavior remain intact. AI lane reset from 3/3 to 0/3; pause for a
      separate PM assignment.
    - Reference:
      `docs/mvp/ai/ai-list-01-seller-image-to-listing-proposal.md`
16. AI-LIST-02 seller proposal review and confirmed application.
    - Status: `AI-LIST-02A/B/C` and mandatory `AI-LIST-CLEAN-P0-02`
      implemented and verified on 2026-07-20. AI lane reset to `0/3`.
    - Reference:
      `docs/mvp/ai/ai-list-02-seller-proposal-review-and-apply-plan.md`
    - `AI-LIST-02A` adds a default-disabled authenticated proposal
      create/resume/dismiss boundary with bounded durable review state,
      actor/listing/media isolation, and no Product write.
    - `AI-LIST-02B` adds the marketplace-account seller review UI, explicit
      selected-field confirmation, ordinary-editor fallback, and
      disabled/network-silent behavior.
      Implemented with a strict proposal client, an accessible responsive
      image/evidence review workbench, replay-safe retry/dismiss handling,
      editable keep/edit/discard decisions, version warnings, and a disabled
      non-mutating apply boundary.
    - `AI-LIST-02C` applies only seller-selected final values through the
      existing Product PATCH plus `If-Match`; Agent Service remains outside
      the authoritative write and no automatic submit/publish is allowed.
      Product success is authoritative, stale writes are never auto-retried,
      and Agent outcome acknowledgement remains deferred.
    - Mandatory cleanup preserved selected-field/category-ID/version
      boundaries and reset the lane from `3/3` to `0/3`.
17. REP-00 listing report domain and policy taxonomy.
18. REP-01 listing report intake, evidence, and persistence.
19. ADM-REP-01 user-reported listing queue and admin override paths.
20. AI-REP-01 report classification in shadow mode.
21. OPS-REP-01 allowlisted reversible report operations.
22. OPS-REP-02 measured policy expansion after rollout gates pass.

Python/FastAPI, a direct OpenAI Responses API provider adapter, Agents SDK
orchestration, OpenSearch-backed hybrid RAG, later LangChain integration,
authenticated-only initial access, and reuse of the existing marketplace chat
UI are approved planning decisions.
Initial report classification remains shadow-only. Autonomous temporary
listing restriction remains deferred until shadow-mode results receive
explicit approval. Core marketplace flows must remain available when AI is
disabled or unavailable.

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

P1 cleanup slices:

1. CLEAN-P1 Staging and quarantine plan.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-staging-and-quarantine-plan.md`
2. CLEAN-P1-01A MVP stage dry run.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-01a-mvp-stage-dry-run.md`
3. CLEAN-P1-02 V2 quarantine checklist.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-02-v2-quarantine-checklist.md`
4. CLEAN-P1-03 AI quarantine checklist.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-03-ai-quarantine-checklist.md`
5. CLEAN-P1-04 Environment cleanup review.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-04-env-cleanup-review.md`
6. CLEAN-P1-05 Teammate-ready PR scope.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/cleanup-p1-05-teammate-ready-pr-scope.md`

P2 cleanup slices:

1. POSTSEARCH-STAB-P2-01 Docs folder structure cleanup.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/fix-05-post-search-profile-stabilization-sprint.md`
2. POSTSEARCH-STAB-P2-02 Manual browser smoke checklist refresh.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p2-02-manual-browser-smoke-checklist-refresh.md`
3. POSTSEARCH-STAB-P2-03 Historical demo and deferred module cleanup.
   - Status: complete.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/postsearch-stab-p2-03-historical-demo-deferred-module-cleanup.md`
4. POSTSEARCH-STAB-P2-04 Observability and demo diagnostics notes.
   - Status: planned.
   - Reference:
     `docs/mvp/fix/stabilization/post-search/fix-05-post-search-profile-stabilization-sprint.md`

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

- Status: V2-COM-00 complete as a documentation-only planning slice.
- Reference: `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`.
- Implementation remains behind the MVP validation entry gate.

Approved slice order:

1. V2-INV-01 inventory initialization, adjustment ledger, and seller UI.
   - Status: complete and browser verified on 2026-07-17.
   - Reference:
     `docs/v2/commerce/v2-inv-01-business-inventory-foundation-plan.md`.
2. V2-CART-01 cart storage/API/UI.
   - Status: complete and browser verified on 2026-07-17.
   - Reference:
     `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`.
3. V2-CART-02 cart validation.
   - Status: complete and browser verified on 2026-07-18.
   - Reference:
     `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`.
4. V2-INV-02 reservation, expiry, release, and commit.
   - Status: complete; live lifecycle and browser cart-boundary verified on
     2026-07-18. Seller-page balance observation remains pending cleanup of
     duplicate local identity fixtures.
   - Reference:
     `docs/v2/commerce/v2-inv-02-inventory-reservation-lifecycle-plan.md`.
5. V2-IAM-01 buyer address book.
   - Status: complete and browser verified on 2026-07-19.
   - Requirement: `IAM-05 Manage addresses`. This is separate from the older
     completed MVP slice named `IAM-05 Protected route test`.
   - Delivered: user-scoped address CRUD/default management, optimistic
     versioning, the internal checkout resolver, gateway routes, account UI,
     metrics, and forward-only persistence migrations.
   - Reference:
     `docs/v2/commerce/v2-iam-01-buyer-address-book-plan.md`.
6. V2-CHK-01 checkout snapshots, totals, and orchestration.
   - Status: implemented with automated verification complete on 2026-07-19.
     The control center approved the local-demo-only tax
     `ZERO_LOCAL_DEMO_V1`, shipping `FREE_LOCAL_DEMO_V1`, policy
     `LOCAL_DEMO_V1`, and `PT15M` lifetime defaults. Production use remains
     prohibited. The coordinated live demo rebuild and browser walkthrough
     remain pending to avoid interrupting concurrent browser stabilization.
   - Delivered: durable checkout/item/address/policy/quote snapshots,
     authoritative local-demo totals, exact cart/address validation,
     all-or-nothing inventory reservation and recovery, one-active-checkout
     enforcement, expiry/cancel/release reconciliation, idempotent commands,
     gateway contracts, and feature-gated buyer review/detail UI.
   - Reference:
     `docs/v2/commerce/v2-chk-01-checkout-snapshots-totals-orchestration-plan.md`.
7. V2-PAY-01 provider intent and verified webhook.
   - Status: payment-service domain foundation, verified HMAC fake-provider
     callback, immutable provider events, terminal history, attempts, and
     transactional `payment.succeeded`/`payment.failed` outbox records are
     implemented and independently verified on 2026-07-20. `V2-PAY-01C` adds
     the default-disabled order-service checkout-to-payment adapter with
     authoritative snapshot mapping, buyer isolation, exact idempotency-key
     forwarding, bounded correlation propagation, and safe downstream failure
     mapping. `V2-PAY-01D` adds the payment-service default-disabled bounded
     outbox dispatcher contract, deterministic payment-intent partitioning,
     concurrent lease claims, acknowledgement-before-publication marking,
     bounded retry/backoff, restart recovery, and safe terminal metadata.
   - Boundary: payment intents and webhooks remain default-off, payment-service
     remains outside the active root reactor, and no gateway, browser, real
     provider, money movement, transfer, payout, refund, Kafka adapter, broker,
     or publication runtime is activated.
8. V2-ORD-01 idempotent order confirmation and reconciliation.
   - Status: `V2-ORD-01A` payment-succeeded order confirmation foundation is
     implemented and independently verified on 2026-07-20.
   - Delivered: default-disabled strict version-1 event handler, authoritative
     local payment binding, durable consumer/event deduplication and payload
     conflict detection, bounded concurrent processing lease, idempotent
     inventory commit, multi-business immutable order snapshots, checkout
     `PAYMENT_PROCESSING -> COMPLETED`, history, and transactional
     `order.confirmed` outbox.
   - `V2-ORD-01B` post-purchase reconciliation: implemented on 2026-08-03.
     Checkout creation now records exact cart-line mutation identities; a
     durable retry worker atomically removes only unchanged purchased lines
     after confirmation and preserves newer buyer edits. Checkout/order store
     names are immutable snapshots, and seller queue copy uses a redacted
     marketplace-buyer label plus order number.
   - Cleanup: `V2-PAY-ORD-CLEAN-P0-01` verified the payment producer/order
     consumer boundary, disabled transport gates, strict non-coercing envelope,
     bounded money and response metadata, provider-event causation identity,
     replay/concurrency/restart behavior, migration immutability, and package
     isolation on 2026-07-20.
   - Boundary: no live transport, Kafka/broker activation, API/UI exposure,
     payment-failed transition, expired-reservation recovery reservation,
     reconciliation queue, refund, transfer, payout, fulfillment, or shipping
     behavior is included.
9. V2-ORD-02 buyer and business order views.
   - `V2-ORD-02A` complete and verified on 2026-07-20: default-off
     authenticated buyer history and detail reads use stored order/group
     statuses, immutable buyer-facing group/item/address/policy snapshots, and
     stable opaque pagination over `(created_at DESC, id DESC)`. Cross-buyer
     and missing detail reads are indistinguishable. Payment internals,
     shipments, and aggregate version are omitted; shipment/version fields may
     be added later without changing existing meanings. The existing buyer
     pagination index is sufficient, so no migration was required.
   - `V2-ORD-02B` complete and verified on 2026-07-20: Auth maps owners to
     `ORDER_VIEW` plus `ORDER_FINANCE_VIEW` and managers to `ORDER_VIEW`;
     default-off business-group queue/detail reads use stable
     business/status pagination, SQL tenant isolation, immutable fulfillment
     snapshots, non-enumerating denial, finance redaction, and the
     query-plan-verified V4 unfiltered queue index.
   - `V2-ORD-02C` complete and source-verified on 2026-07-20: the
     management-style business portal queue/detail UI uses only the green 02B
     read contract. Independent Angular and gateway flags remain default-off;
     disabled routes/navigation make zero Order requests, while the enabled
     authenticated BFF route relays the trusted token and strips spoofed
     identity headers. It adds no fulfillment mutation.
   - Reference:
     `docs/v2/commerce/v2-ord-02c-business-fulfillment-ui.md`.
   - `V2-ORD-CLEAN-P0-01` completed on 2026-07-20: malformed cursor
     timestamps now use bounded `400` contracts, Auth dependency throttling is
     no longer hidden as membership denial, and the Angular route/navigation/
     component boundary shares one default-off capability source. Existing
     SQL isolation, finance omission, gateway header stripping, and V3/V4
     query-index intent were preserved and re-audited without a migration.
     The disposable MySQL rerun was unavailable because the local Docker
     daemon was stopped; all feasible unit, controller, gateway, and frontend
     suites remained green.
   - Business lane reset from `3/3` to `0/3` and is paused before shipping.
10. V2-ORD-03 cancellation, refund, and inventory compensation.
11. V2-SHP-01 fulfillment and shipping.
   - `V2-SHP-01A` has an approved authoritative default-off contract for
     owner-only acceptance of a paid `PENDING_ACCEPTANCE` business group.
     Forward-only Order V5 owns optimistic group versioning, durable P7D
     command idempotency, append-only group history, and transactional
     `business_order.accepted` outbox persistence. Source verification is
     complete; its MySQL concurrency failures were resolved on 2026-08-03 by
     moving expiry purge outside the mutation transaction and retrying only
     fresh-transaction concurrency victims.
   - `V2-SHP-01B/C` bounded local-demo fulfillment is implemented and
     source/MySQL/UI verified on 2026-08-03. Order V8 owns
     `ACCEPTED -> PROCESSING -> SHIPPED -> DELIVERED`, exactly one manual
     shipment per business group, append-only group/shipment histories,
     durable idempotency, optimistic locking, and transactional outbox rows.
     `DELIVERED` is explicitly a local simulation. Browser verification is
     pending.
   - Reference:
     `docs/v2/commerce/v2-shp-01a-accept-paid-business-fulfillment-group.md`.
     `docs/v2/commerce/v2-shp-01b-c-bounded-manual-fulfillment.md`.
12. V2-NOT-01 notifications.
   - `V2-NOT-01A` reconstructs Notification Service for default-off,
     Notification-owned MySQL persistence and a direct/fake
     `order.confirmed` v2 buyer notification consumer. Order continues to
     produce its unchanged v1 event and atomically adds recipient-bearing v2.
   - `V2-NOT-01B` adds default-off authenticated list, mark-one-read, and
     mark-all-read APIs using the existing Auth `/api/v1/users/me` bearer-relay
     identity contract and Notification-owned recipient SQL isolation. Source
     and offline tests are complete; the disposable MySQL gate remains
     unavailable locally, so the business lane remains `0/3`.
   - `V2-NOT-01C` adds an independent default-off gateway
     `/api/v1/notifications/**` boundary and Angular account notification
     center over the NOT-01B API. It remains local/source-only; NOT-01A/B
     disposable MySQL verification is still mandatory before the business lane
     can advance.
   - `V2-NOT-01D` maps the authoritative confirmation, acceptance,
     processing, shipment, demo-delivery, and completed-cancellation outbox
     events into durable buyer/business in-app notifications. It adds
     retryable local HTTP delivery, server-authoritative badge counts, seller
     and expanded buyer centers, and bounded commerce-runtime activation
     without changing a commerce state machine.
   - Kafka, WebSockets, email, SMS, push, preferences, purge, marketing, and
     provider delivery remain deferred.
   - Reference:
     `docs/v2/commerce/v2-not-01a-order-confirmed-in-app-notification.md`.
   - Reference:
     `docs/v2/commerce/v2-not-01b-authenticated-notification-read-api.md`.
   - Reference:
     `docs/v2/commerce/v2-not-01c-notification-center-ui-gateway.md`.
   - Reference:
     `docs/v2/commerce/v2-not-01d-event-driven-commerce-notifications.md`.
13. V2-RET-01 post-delivery business-group returns.
   - Dedicated default-off return aggregate, deterministic 30-day local-demo
     policy, demo shipment, explicit disposition, group-merchandise fake refund,
     event-driven notifications, and isolated buyer/seller UI.
   - Reference:
     `docs/v2/commerce/v2-ret-01-post-delivery-business-group-returns.md`.

### V3

Trust, intelligence, and growth:

- Individual trade completion verification and public completed-sales count
- Reviews/reputation
- Advanced admin/trust operations
- AI assistant
- Advanced analytics

## 8. Approved Near-Term Product Delivery Sequence

Status: approved on 2026-07-20 after reviewing the implemented commerce and AI
boundaries against established open-source multi-vendor workflow patterns.

The objective is to finish seller- and buyer-visible workflows rather than add
another provider, framework, or infrastructure foundation. All incomplete
paths remain default-off.

### 8.1 Current checkpoint

| Lane | Counter | Completed checkpoint | Next approved slice |
|---|---:|---|---|
| Business | `0/3` | `V2-ORD-CLEAN-P0-01` buyer/business order read-surface cleanup | Await PM dispatch before `V2-SHP-01A` |
| AI | `0/3` | `AI-CS-CLEAN-P0-03` local LangChain/chat/demo/stabilization cleanup | Await separate dispatch for approved local-only `AI-DISC-01A` |

Payment/provider activation, real money movement, live Kafka transport,
LangGraph behavior, live AI rollout, and autonomous AI actions remain outside
this sequence.

### 8.2 Execution waves

| Wave | Business lane | AI lane | Coordination rule |
|---|---|---|---|
| 1 | `V2-ORD-02B` backend business queue/detail and permissions | `AI-LIST-02A` backend proposal session/review API | May run in parallel; Order/Auth and Agent/Product ownership are isolated |
| 2A | `V2-ORD-02C` business portal queue/detail UI | Paused | Own shared Angular/gateway/browser surfaces |
| 2B | Completed at business `3/3` | `AI-LIST-02B` marketplace seller proposal review UI | Shared frontend/browser ownership remained serialized |
| 3A | `V2-ORD-CLEAN-P0-01` complete; business reset to `0/3` and paused | `AI-LIST-02B` complete at `2/3` | No business successor without PM dispatch |
| 3B | Begin Order-only `V2-SHP-01A` only after cleanup | `AI-LIST-02C` confirmed Product PATCH application completed | Ownership remained separate from Order-only work |
| 4 | Continue `V2-SHP-01B/C`, then shipping cleanup | Mandatory AI-LIST-02 cleanup completed; later AI-CS local batch also cleaned | Preserve one bounded slice per lane and serialize browser/runtime work |

### 8.3 Business continuation after ORD-02

1. `V2-SHP-01A`: accept a paid business fulfillment group.
2. `V2-SHP-01B`: create partial shipments with item quantities.
3. `V2-SHP-01C`: mark shipped, expose buyer shipment snapshots, and derive
   aggregate order status.
4. Mandatory shipping cleanup.
5. `V2-ORD-03A`: cancellation request and eligibility state.
6. `V2-ORD-03B`: idempotent refund orchestration.
7. `V2-ORD-03C`: idempotent inventory compensation.
8. `V2-PAY-02A`: payment/order reconciliation and admin operations queue.
9. `V2-NOT-01A`: buyer `ORDER_CONFIRMED` persistence and fake consumer.
10. `V2-NOT-01B`: authenticated buyer read API.
11. `V2-NOT-01C`: default-off notification center UI and gateway boundary.
12. `V2-NOT-01D`: bounded transport, buyer/business event projections,
    server-authoritative badge/count behavior, runtime and browser acceptance.
13. Later `V2-NOT-01` slices: preferences, email, and other channels only
    after separate product/provider approval.

No real payment provider is activated until reconciliation, recovery,
production legal/provider decisions, and explicit rollout approval are green.

### 8.4 AI continuation after AI-LIST-02

1. `AI-LIST-02A/B/C` and mandatory cleanup are complete and default-off.
2. `AI-CS-02B/02C` plus bounded BFF-session and validation-diagnostic
   stabilization are source-complete and reconciled by
   `AI-CS-CLEAN-P0-03`; all committed gates remain false.
3. Keep proposal application seller-confirmed and Product-versioned in every
   future cohort. Production quality, latency, cost, privacy, and rollout
   evidence remain unknown or unapproved.
4. Source changes are verified locally and deployed/browser-tested only in
   explicit batches owned by a separate task.
5. The next approved local direction is `AI-DISC-01A`: official LangChain v1
   `create_agent`, strict `ToolStrategy(DiscoveryTurnResult)`, typed allowlisted
   Product tools, server-owned/MySQL-authoritative state, ephemeral per-request
   ReAct, fixed model/tool/time budgets, deterministic final guardrails, and
   three-to-five detail-revalidated recommendations. It has no implementation
   or completion status yet and requires a separate assignment; the full
   pinned `langchain` dependency belongs only to that future offline-fake
   slice.
6. Keep all automated report/listing operations disabled until shadow-mode
   quality, appeal, restoration, and explicit approval gates pass.

### 8.5 Release-oriented acceptance

The next checkpoint is not measured only by test totals. It requires:

- a buyer can read their immutable business order;
- authorized business staff can read only their fulfillment group;
- a seller can generate, review, edit, and explicitly apply an AI proposal
  without automatic submission or publication;
- disabled AI/commerce flags preserve the existing marketplace;
- each state-changing command has an actor, precondition, idempotency key,
  immutable history/outbox effect, and explicit compensation boundary; and
- shared frontend, browser, gateway, and runtime ownership remains serialized.

### 8.6 V2 commerce release-candidate stabilization

- `V2-COM-RC-01`: one non-feature stabilization gate for the completed bounded
  V2 commerce lifecycle. It standardizes the local composition and compiled
  frontend profile, clean MySQL 8.4 migrations, deterministic reusable
  fixtures, real-service fulfillment/return and cancellation journeys,
  restart/replay and concurrency gates, authorization/accounting invariants,
  safe diagnostics, browser acceptance, CI, and the authoritative runtime
  runbook. Real providers, carriers, payouts, and later-release features remain
  deferred. See `docs/v2/commerce/v2-com-rc-01-commerce-release-candidate-gate.md`.
