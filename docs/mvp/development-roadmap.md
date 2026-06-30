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
- Individual seller profile: IND-01 and IND-02
- Business onboarding/admin decision: BUS-01 through BUS-04
- Listing foundation, drafts, media, submit, admin decision, and public detail:
  LIST-00 through LIST-07
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
  `docs/mvp/fix/fix-02-stabilization-sprint-plan.md`.

Do not start V2 or V3 features until the MVP browser, seller, listing, search,
chat, and moderation paths are validated.

Business inventory, cart, checkout, payment, orders, shipping, and
notifications remain V2 even though the seller portal exists in MVP.

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

- `docs/mvp/iam/iam-authentication-accounts-implementation-plan.md`

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| IAM-00 Legacy auth cleanup plan | Complete | `docs/mvp/iam/iam-00-legacy-auth-cleanup-plan.md` |
| IAM-01 Keycloak local setup | Complete | `docs/mvp/iam/iam-01-keycloak-local-setup.md` |
| IAM-02 Gateway BFF login/logout/session | Complete | `docs/mvp/iam/iam-02-gateway-bff-login-logout-session.md` |
| IAM-03 Identity user table and Keycloak `sub` mapping | Complete | `docs/mvp/iam/iam-03-identity-user-keycloak-sub-mapping.md` |
| IAM-04 Frontend auth session awareness | Complete | `docs/mvp/iam/iam-04-frontend-auth-session-awareness.md` |
| IAM-05 Protected route test | Complete | `docs/mvp/iam/iam-05-protected-route-test.md` |
| IAM-06 Profile view/edit | Complete | `docs/mvp/iam/iam-06-profile-view-edit.md` |

Remaining account work before MVP completion:

- Verify the deployed demo auth/session flow with test users.
- Keep registration/password recovery lifecycle owned by Keycloak.
- Do not reintroduce browser token storage or custom JWT issuance.

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

Status: business application and approval path implemented; store profile is
not complete.

Completed slices:

| Slice | Status | Reference |
| --- | --- | --- |
| BUS-01 Business application draft | Complete | `docs/mvp/bus/bus-01-business-application-draft.md` |
| BUS-02 Submit business application | Complete | `docs/mvp/bus/bus-02-submit-business-application.md` |
| BUS-03/BUS-04 Verification callback and admin decision | Complete | `docs/mvp/bus/bus-03-04-business-verification-and-admin-decision.md` |

Remaining MVP work:

- BUS-05 basic store profile management.
- Business seller portal flow for viewing approved business/store state.
- Basic business listing management after store profile exists.
- Stronger admin review queue UX beyond entering an application ID manually.
- Inventory, orders, fulfillment, and payment management remain V2.

### 5.4 Listings And Media

Status: listing schema, categories, draft creation, media metadata request and
confirm, ordered draft image attachment, draft editing, submission for review,
admin listing moderation decisions, public approved listing detail, public
browse, and object-storage-backed image delivery are implemented.

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

Recommended next search/storefront slices:

1. IND-03 implementation: move individual seller personal listing routes into
   marketplace/account routes.
2. SEARCH-02 public business storefront.
3. SEARCH-03 search filters, sorting, and cursor pagination.

Do not implement platform checkout, inventory reservation, or business orders
as part of listing work.

### 5.5 Search And Storefront

Status: read model plan and initial database-backed public approved listing
browse are implemented.

MVP goal:

- Guests can visit the public site and see approved goods without logging in.
- The public marketplace should feel like a shopping site rather than an
  admin dashboard.
- Listing and storefront pages clearly distinguish individual sellers from
  business sellers.
- Individual listings show that payment and delivery are arranged
  off-platform.

Recommended slices:

1. SEARCH-00 search/storefront read model plan.
   - Status: complete.
   - Reference: `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
2. SEARCH-01 public approved listing browse without OpenSearch.
   - Status: complete.
   - Reference: `docs/mvp/search/search-01-public-approved-listing-browse.md`
3. SEARCH-02 public business storefront page.
4. SEARCH-03 search filters, sorting, and cursor pagination.
5. SEARCH-04 OpenSearch projection only after the database-backed read path is
   correct.

### 5.6 Basic Buyer/Seller Chat

Status: not started.

Recommended slices:

1. CHAT-00 chat domain and authorization plan.
2. CHAT-01 start listing conversation.
3. CHAT-02 send/read text messages.
4. CHAT-03 conversation list and unread state.

Individual trade creation and completion remain V3.

### 5.7 Basic Admin Moderation

Status: business application admin decision and basic listing moderation
decision are complete.

Recommended slices:

1. ADM-LIST-00 moderation case foundation for richer listing review queues.
2. ADM-LIST-01 listing submission creates a moderation case.
3. ADM-LIST-03 seller-facing decision reason/status.

Advanced reports, suspensions, support cases, operations queues, and disputes
remain V3.

## 6. Immediate Next Work

### FIX-02 Stabilization Sprint

Status: planned.

Reference:

- `docs/mvp/fix/fix-02-stabilization-sprint-plan.md`

Goal:

- Clean up completed MVP work before more teammates start coding.
- Do not add features.
- Fix route/product-surface drift, legacy auth leftovers, V2 demo leakage,
  duplicate docs, and baseline verification gaps.

P0 cleanup slices:

1. STAB-P0-01 Stabilize Git state and verification baseline.
   - Status: complete.
   - Reference: `docs/mvp/fix/stab-p0-01-git-verification-baseline.md`
2. STAB-P0-02 Remove individual seller tools from business seller portal.
   - Status: complete.
   - Reference: `docs/mvp/fix/stab-p0-02-business-seller-portal-boundary.md`
3. STAB-P0-03 Remove or quarantine V2 demo UI from active MVP navigation.
   - Status: complete.
   - Reference: `docs/mvp/fix/stab-p0-03-v2-demo-ui-quarantine.md`
4. STAB-P0-04 Remove legacy custom JWT issuance path.
   - Status: complete.
   - Reference: `docs/mvp/fix/stab-p0-04-legacy-jwt-cleanup.md`
5. STAB-P0-05 Fix documentation duplicates and slice naming drift.

### FIX-01 Restore Verification Baseline

Status: complete.

Reference:

- `docs/mvp/fix/fix-01-restore-verification-baseline.md`

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
   - Defer OpenSearch until database-backed browse is correct.

9. SEARCH-01: Public approved listing browse.
   - Status: complete.
   - Let guests browse approved listings.
   - Show newest approved active listings on the marketplace homepage.
   - Do not add OpenSearch yet.

10. SEARCH-02: Public business storefront.
    - Let guests view an active business storefront.
    - Show approved active business listings.
    - Hide suspended and private data.

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
