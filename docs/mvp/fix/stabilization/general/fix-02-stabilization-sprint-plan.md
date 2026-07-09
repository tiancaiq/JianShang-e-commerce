# FIX-02 Stabilization Sprint Plan

Status: planned.

## Scope

This stabilization sprint reviews completed MVP work before more teammates
start coding.

Completed scope reviewed:

- IAM-00 through IAM-06
- IND-01 and IND-02
- BUS-01 through BUS-04
- LIST-00 through LIST-07
- Media upload/storage work documented as `MEDIA-01` in the roadmap
- Current marketplace UI and logical three-site separation docs

This sprint must not add product features. Each cleanup slice should be small
enough for one pull request.

## Review Summary

The project is usable for continued MVP work, but a few things will confuse
teammates if they start now:

- Individual seller flows are partly migrated to marketplace account routes,
  but the business seller portal still exposes individual seller links.
- Legacy custom JWT classes and DTOs remain after the Keycloak BFF decision.
- V2 tutorial/demo order, payment, inventory, and product screens/services are
  still present and can blur MVP boundaries.
- Some docs still exist in both old and new locations.
- Backend has repeated request parsing and validation patterns that should be
  shared through small helpers.
- Public browse is currently client-filtered and server-limited, which is OK
  for demo but should be documented before scaling claims.
- Test coverage is stronger on frontend services/components than on backend
  authorization edge cases for later listing/media/admin paths.

## P0: Must Fix Before Teammates Start

### STAB-P0-01 Stabilize Git State And Verification Baseline

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p0-01-git-verification-baseline.md`

Problem:

- The working tree contains many modified and untracked MVP files.
- Teammates need a clean baseline that passes the agreed commands.

Likely files:

- `.github/workflows/pull-request-quality.yml`
- `docs/mvp/fix/stabilization/general/fix-01-restore-verification-baseline.md`
- `docs/deploy/team-demo-readme.md`
- Any intentionally completed but untracked docs/code from LIST-07, media,
  search, and UI work

Tasks:

- Decide which untracked files are intentional MVP work and include them.
- Remove or ignore accidental generated/demo files.
- Run frontend tests and build.
- Run backend tests where Docker/Testcontainers is available.
- Record exact verification command results.

Tests:

- `npm.cmd test -- --watch=false --browsers=ChromeHeadless`
- `npm.cmd run build`
- `.\mvnw.cmd test` or CI equivalent with Docker available

Acceptance criteria:

- `git status --short` contains only intentional current-PR changes.
- Frontend test/build result is documented.
- Backend test result is documented or CI proves it.
- No product behavior changes.

### STAB-P0-02 Remove Individual Seller Tools From Business Seller Portal

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p0-02-business-seller-portal-boundary.md`

Problem:

- Individual sellers should use the marketplace/account site.
- `SellerLayoutComponent` still shows `Listings`, `New Listing`, and
  `Individual Seller` links under `/seller`.
- Existing docs define target marketplace routes in IND-03.

Likely files:

- `frontend/src/app/layout/seller-layout/seller-layout.component.ts`
- `frontend/src/app/app.routes.ts`
- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.spec.ts`
- `docs/mvp/ind/ind-03-marketplace-individual-selling-plan.md`

Tasks:

- Keep `/seller` as business-only navigation.
- Move individual activation/listing entry points to marketplace/account
  routes.
- Keep legacy `/seller/...` redirects only for compatibility.
- Update route tests.

Tests:

- Frontend route tests.
- Seller layout component test.
- Listing management route-selection tests.

Acceptance criteria:

- Business seller portal no longer displays personal individual listing tools.
- Marketplace account routes remain protected and usable.
- Legacy routes redirect instead of becoming primary navigation.

### STAB-P0-03 Remove Or Quarantine V2 Demo UI From Active MVP Navigation

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p0-03-v2-demo-ui-quarantine.md`

Problem:

- Frontend still contains tutorial/demo screens for dashboard, products,
  inventory, orders, and payments.
- These are V2 or tutorial concepts and can mislead teammates or demo users.

Likely files:

- `frontend/src/app/features/dashboard/dashboard.component.ts`
- `frontend/src/app/features/products/*`
- `frontend/src/app/features/inventory/*`
- `frontend/src/app/features/orders/*`
- `frontend/src/app/features/payments/*`
- `frontend/src/app/core/services/order.service.ts`
- `frontend/src/app/core/services/payment.service.ts`
- `frontend/src/app/core/services/inventory.service.ts`
- `frontend/src/app/core/models/order.model.ts`
- `frontend/src/app/core/models/payment.model.ts`
- `frontend/src/app/app.routes.ts`

Tasks:

- Remove active navigation to V2/tutorial pages.
- Either delete unused demo components or place them under a clearly named
  legacy/tutorial folder not wired into routes.
- Ensure no MVP page links to cart, checkout, inventory, order, payment, or
  wallet behavior.

Tests:

- Frontend route tests.
- Layout tests for marketplace, seller, and admin navigation.
- Existing frontend suite.

Acceptance criteria:

- No visible MVP navigation reaches V2/demo screens.
- No business seller portal page implies inventory, orders, or payments are
  available.
- No backend feature behavior is added.

### STAB-P0-04 Remove Legacy Custom JWT Issuance Path

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p0-04-legacy-jwt-cleanup.md`

Problem:

- ADR-0001 chose Keycloak OIDC with gateway BFF sessions.
- `JwtService`, `LoginRequest`, and `SignupRequest` still exist in
  `auth-service`.
- Keeping legacy JWT code increases security confusion.

Likely files:

- `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/LoginRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/SignupRequest.java`
- `auth-service/pom.xml`
- `docs/mvp/iam/core/iam-00-legacy-auth-cleanup-plan.md`

Tasks:

- Confirm no active controller uses custom login/signup/JWT issuance.
- Remove unused legacy JWT code and dependencies if unused.
- Keep Keycloak user mapping and BFF session logic unchanged.
- Update IAM-00 cleanup status.

Tests:

- `.\mvnw.cmd -pl auth-service -am test`
- Gateway auth BFF tests.
- Search for `localStorage`, `JwtService`, `LoginRequest`, and `SignupRequest`.

Acceptance criteria:

- No custom JWT issuance code remains in active auth-service source.
- Browser token storage remains absent.
- Keycloak/BFF session tests still pass.

### STAB-P0-05 Fix Documentation Duplicates And Slice Naming Drift

Status: complete.

Problem:

- Slice docs exist in both old and new locations:
  `docs/mvp/list-02-media-upload-request-confirm.md` and
  `docs/mvp/list/list-02-media-upload-request-confirm.md`.
- `docs/mvp/fix-01-restore-verification-baseline.md` duplicates
  `docs/mvp/fix/stabilization/general/fix-01-restore-verification-baseline.md`.
- User-facing naming says `Media-00`, while roadmap currently references
  `MEDIA-01`.

Likely files:

- `docs/mvp/development-roadmap.md`
- `docs/mvp/list-02-media-upload-request-confirm.md`
- `docs/mvp/fix-01-restore-verification-baseline.md`
- `docs/mvp/list/media-01-object-storage-image-delivery.md`
- `AGENTS.md`

Tasks:

- Keep slice docs only in approved feature folders.
- Replace old top-level duplicates with short redirect notes or remove them.
- Pick one media slice name and use it consistently.
- Update roadmap references.

Tests:

- `rg -n "list-02-media-upload-request-confirm|fix-01-restore|Media-00|MEDIA-01" docs AGENTS.md`
- `git diff --check`

Acceptance criteria:

- Teammates can find one source of truth for each slice.
- Roadmap, AGENTS, and slice docs use consistent naming.

Completion note:

- Top-level `docs/mvp/list-02-media-upload-request-confirm.md` is now a
  redirect note to `docs/mvp/list/list-02-media-upload-request-confirm.md`.
- Top-level `docs/mvp/fix-01-restore-verification-baseline.md` is now a
  redirect note to `docs/mvp/fix/stabilization/general/fix-01-restore-verification-baseline.md`.
- `SITE-00` source content now lives in
  `docs/mvp/site/site-00-logical-three-site-separation.md`; the old top-level
  file is a redirect note.
- `AGENTS.md` now lists `docs/mvp/search/` and `docs/mvp/site/` as approved
  feature folders.

## P1: Should Fix Soon

### STAB-P1-01 Extract Shared `If-Match` Version Parsing

Problem:

- `AuthController` and `ListingController` duplicate optimistic-locking
  version parsing.
- Error text and status mapping can drift across services.

Likely files:

- `common-web/src/main/java/...`
- `auth-service/src/main/java/.../AuthController.java`
- `product-service/src/main/java/.../ListingController.java`
- Common-web tests

Tasks:

- Add a small common-web helper for required numeric `If-Match`.
- Replace duplicated private parsing methods.
- Preserve current API behavior.

Tests:

- Common-web unit tests for quoted, unquoted, missing, and invalid versions.
- Auth-service and product-service tests.

Acceptance criteria:

- One helper owns `If-Match` parsing.
- Missing/stale/invalid version behavior stays compatible.

### STAB-P1-02 Add Backend Authorization Tests For Listing/Media/Admin Edges

Problem:

- Product-service has API tests, but completed listing/media/admin paths need
  stronger negative authorization coverage before teammates build chat and
  storefronts on top.

Likely files:

- `product-service/src/test/java/.../ListingDraftApiTests.java`
- Product-service test fixtures

Tasks:

- Test cross-user individual listing read/edit denial.
- Test cross-business listing denial.
- Test media read/attach denial for non-owner.
- Test admin moderation denial for non-admin.
- Test public listing hides draft/rejected/private data.

Tests:

- `.\mvnw.cmd -pl product-service -am test`

Acceptance criteria:

- Critical authorization invariants have negative tests.
- No production behavior changes unless a bug is found.

### STAB-P1-03 Normalize Frontend API Response Handling

Status: complete.

Problem:

- Some backend endpoints return raw arrays/objects while auth-service often
  uses `ApiDataResponse`.
- Frontend services hide this inconsistency ad hoc.

Likely files:

- `docs/mvp/api-contract.md`
- `frontend/src/app/core/services/*.ts`
- `product-service/src/main/java/.../ListingController.java`

Tasks:

- Decide whether MVP product endpoints stay raw or move to the standard data
  envelope.
- Document the decision.
- If changing behavior, do it in one small follow-up PR with tests.

Tests:

- Frontend service tests.
- Product-service API tests.

Acceptance criteria:

- API response shape is documented and consistent enough for teammates.
- New services know which response pattern to use.

Completion note:

- `docs/mvp/api-contract.md` documents the current MVP response-shape
  decision.
- Angular core services unwrap `ApiDataResponse<T>` before component code sees
  it.

### STAB-P1-04 Clean Up Marketplace UI Route And Styling Inconsistencies

Status: complete.

Problem:

- Marketplace pages use the new light shopping style, while account listing
  pages still use dark portal tokens.
- Marketplace homepage still links `List an Item` to `/seller/listings/new`
  in one place.

Likely files:

- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/listings/listing-management.component.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- `frontend/src/styles.css`

Tasks:

- Replace stale `/seller/listings/new` links with `/account/listings/new`.
- Make marketplace account listing pages visually compatible with marketplace
  layout.
- Keep business seller portal dashboard styling separate.

Tests:

- Marketplace home component test.
- Listing management/form component tests.
- Visual/manual smoke check on desktop and mobile widths.

Acceptance criteria:

- Public marketplace and marketplace account flows feel connected.
- Seller/admin dashboard style remains separate.

Completion note:

- Marketplace `Sell` and `List an Item` links now target
  `/account/listings/new` directly.
- Listing management and draft form styles use marketplace-compatible tokens
  when rendered inside `MarketplaceLayoutComponent`.
- Business seller and admin dashboard styling remains separate.

### STAB-P1-05 Tighten Public Browse Performance Contract

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p1-05-public-browse-performance-contract.md`

Problem:

- Public listing browse currently returns a fixed newest list and filters on
  the client.
- This is acceptable for early MVP but not for 100K+ user expectations.

Likely files:

- `product-service/src/main/java/.../ListingController.java`
- `product-service/src/main/java/.../ListingService.java`
- `product-service/src/main/java/.../ListingDraftRepository.java`
- `docs/mvp/search/search-01-public-approved-listing-browse.md`

Tasks:

- Document current fixed-limit behavior clearly.
- Add a small server-side limit constant/config with tests if missing.
- Add indexes only if current query patterns are not covered.
- Defer full filters/cursor pagination to SEARCH-03.

Tests:

- Product-service public browse tests.
- Migration test if an index is added.

Acceptance criteria:

- Current browse cannot accidentally return unbounded data.
- SEARCH-03 remains the feature slice for real filters/pagination.

### STAB-P1-06 Reduce Large Component Responsibilities

Status: complete.

Problem:

- `ListingDraftFormComponent` owns form state, validation, media upload,
  image attachment, submission, routing, and toast handling.
- This will get harder to maintain as business and individual flows diverge.

Likely files:

- `frontend/src/app/features/listings/listing-draft-form.component.ts`
- New small helper/service/component files under listings

Tasks:

- Extract media upload orchestration into a focused helper/service.
- Extract listing-form request building/validation into a small pure helper.
- Preserve templates and behavior.

Tests:

- Existing listing draft form tests.
- New helper unit tests.

Acceptance criteria:

- Component is smaller without changing user behavior.
- Media and validation rules have focused tests.

Completion note:

- Draft request building, draft validation, API-to-form mapping, selected-image
  validation, and image request construction live in a pure helper.
- The media upload request, byte upload, confirmation, and image attachment
  flow lives in a focused Angular service.
- Existing listing draft form behavior is preserved.

## P2: Can Wait

### STAB-P2-01 Remove Or Archive Tutorial Microservice Stubs

Status: complete.

Problem:

- `order-service`, `inventory-service`, `payment-service`, and
  `notification-service` still exist from tutorial/V2 direction.
- They may remain for future V2, but they should not distract MVP teammates.

Likely files:

- Root `pom.xml`
- Service folders for order, inventory, payment, notification
- `docs/mvp/architecture.md`

Tasks:

- Decide whether to keep compiling these modules in MVP CI.
- If kept, mark them clearly as deferred/V2 stubs.
- If removed from active build, document how V2 will reintroduce them.

Tests:

- Maven build for whichever module set remains active.
- Architecture checks.

Acceptance criteria:

- Teammates understand these services are not MVP feature surfaces.

Completion note:

- `order-service`, `inventory-service`, `payment-service`, and
  `notification-service` remain in the repository as archived V2/tutorial
  reference code.
- The active Maven reactor, migration validation job, and architecture
  guardrails now cover only active MVP backend modules.
- V2 work must reintroduce real service modules through an approved V2
  architecture slice.

### STAB-P2-02 Add Frontend Shared UI Primitives

Status: complete.

Problem:

- Buttons, empty states, status badges, cards, and table styles are repeated
  across standalone components.

Likely files:

- `frontend/src/app/shared/components/*`
- Marketplace/listing/business/admin components

Tasks:

- Create only primitives that remove existing duplication.
- Avoid building a large design system before MVP needs it.

Tests:

- Component tests for shared primitives if logic exists.
- Existing frontend suite.

Acceptance criteria:

- Repeated UI markup decreases.
- Product surfaces still keep distinct visual styles.

Completion note:

- Added shared `StatusPillComponent` and `EmptyStateComponent` primitives.
- Reused them in marketplace account profile/listing pages.
- Added `AGENTS.md` guidance to keep shared UI primitives small,
  theme-token-driven, and surface-aware.

### STAB-P2-03 Normalize ID And Text Validation Helpers

Status: complete.

Problem:

- Services repeat ULID length checks, text trimming, max-length validation,
  and enum normalization.

Likely files:

- `common-core`
- `product-service`
- `auth-service`

Tasks:

- Extract only stable, non-domain-specific helpers.
- Do not move business rules into shared modules.

Tests:

- Common-core unit tests.
- Affected service tests.

Acceptance criteria:

- Repeated generic validation decreases.
- Domain-specific validation remains in owning services.

Completion note:

- Added `common-core` helpers for collapsed text normalization and
  fixed-length ID trimming.
- Reused helpers in business application, individual seller, and listing
  services.
- Kept enum, phone, email, URL, moderation decision, and seller/business
  listing rules inside their owning services.

### STAB-P2-04 Add Basic Observability For Listing And Auth Decisions

Status: complete.

Problem:

- Logs exist for key listing changes, but metrics/tracing for important MVP
  decisions are still thin.

Likely files:

- `auth-service`
- `product-service`
- `api-gateway`

Tasks:

- Add structured logs for denied auth, moderation decisions, and media
  failures.
- Add simple counters only where the stack already supports them.

Tests:

- Unit tests only for logic added around event/counter names if practical.

Acceptance criteria:

- Important failure paths are easier to debug in demo and CI logs.

Completion note:

- Added warning logs for denied platform admin/business/listing authorization,
  webhook signature denial, moderation state/version failures, and listing
  media verification failures.
- Did not add metrics dependencies because auth-service and product-service do
  not currently expose Micrometer/Actuator; counters should wait for an
  approved observability stack decision.

### STAB-P2-05 Create Teammate Onboarding Checklist

Problem:

- There are many docs, but a teammate starting tomorrow needs one short path.

Likely files:

- `docs/deploy/team-demo-readme.md`
- `README.md`
- `docs/mvp/development-roadmap.md`

Tasks:

- Write the minimum local setup and coding workflow.
- Link MVP source-of-truth docs.
- Explain what not to implement yet.

Tests:

- Documentation link check by `rg`/manual review.

Acceptance criteria:

- A teammate can run the app, run tests, pick one slice, and avoid V2/V3 work.

### STAB-P2-06 Active Backend Migration Boundary

Status: complete.

Reference:

- `docs/mvp/fix/stabilization/general/stab-p2-06-active-backend-migration-boundary.md`

Problem:

- The active Maven reactor now includes `common-storage` and `chat-service`.
- Architecture guardrails still only checked the earlier MVP service set.
- Active Flyway migrations use service-owned subfolders, but the repository
  still contains archived root-level tutorial SQL that can confuse future
  backend work.

Tasks:

- Include `chat-service` and `common-storage` in architecture guardrails.
- Pin active Flyway locations for identity, catalog, and chat schemas.
- Reject new root-level Flyway SQL for active MVP services.
- Mark the old auth-service root migration as archived documentation-only
  history.

Tests:

- `python tools/architecture_checks.py`
- `python tools/architecture_checks.py --self-test`

Acceptance criteria:

- Backend boundary checks match the active MVP module set.
- Teammates know where new service-owned migrations belong.
- No schema or product behavior changes.

## Recommended Order

1. STAB-P0-01
2. STAB-P0-02
3. STAB-P0-03
4. STAB-P0-04
5. STAB-P0-05
6. STAB-P1-02
7. STAB-P1-04
8. Continue remaining P1/P2 as capacity allows

After P0 is complete, teammates can safely start small MVP slices again.
