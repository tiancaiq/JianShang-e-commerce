# CLEAN-P1-01A MVP Stage Dry Run

Status: Complete  
Date: 2026-07-19

## Goal

Define the exact MVP-only staging boundary before creating a teammate-facing
pull request.

This dry run does not stage files, delete files, edit environment files, or
change application behavior.

## Implementation Result

The current worktree was inspected with:

```powershell
git status --short --untracked-files=all
git diff --name-only
```

Result: the workspace is not ready for whole-file staging. It contains MVP,
V2, V3/AI, CI, runtime, local environment, and generated-artifact work at the
same time. The MVP PR should be assembled by hunk staging mixed files and by
excluding quarantined feature groups.

No files were staged during this dry run. No environment files were edited.

## MVP PR Intent

The first cleanup PR should prove that the current MVP baseline is coherent:

- public marketplace browse/search/detail works for guests.
- account/session/profile/logout behavior is stable.
- individual listing management and basic chat remain in the marketplace site.
- business and admin surfaces remain separate management-style route groups.
- backend tests and frontend tests/build have a repeatable command set.

## Stage As MVP

These changes are good candidates for the first MVP stabilization PR after a
final file-level review.

### Verification Baseline

- `.gitignore`
  - Include local generated artifact ignores:
    - `.playwright-mcp/`
    - `marketplace-images-*.png`
    - `*.egg-info/`
- `pom.xml`
  - Include only the Surefire `net.bytebuddy.experimental=true` test setting.
  - Do not include the `inventory-service` or `order-service` module additions
    in this MVP PR.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
  - Include the chat test double fix for `conversationRead$` and the methods
    currently required by `FloatingChatComponent`.
  - Include logout/session expectations only if the matching MVP logout code is
    also included.
  - Do not include cart-specific expectations.
- `docs/mvp/fix/stabilization/post-search/cleanup-p0-worktree-boundary-and-verification.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-staging-and-quarantine-plan.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-01a-mvp-stage-dry-run.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-02-v2-quarantine-checklist.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-03-ai-quarantine-checklist.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-04-env-cleanup-review.md`
- `docs/mvp/fix/stabilization/post-search/cleanup-p1-05-teammate-ready-pr-scope.md`
- `docs/mvp/development-roadmap.md`
  - Include only the cleanup references and MVP stabilization status.
  - Exclude V2/V3 implementation-status expansions from an MVP-only PR.

### Auth, Session, And Profile

Likely MVP paths:

- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/ApiGatewayApplicationTests.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthServiceExceptionHandler.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/PublicIdentityLabelController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/BusinessIdentityLabelResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/repository/UserRepository.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AdminAuthorizationService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/BusinessMembershipService.java`
- `auth-service/src/main/resources/application.properties`
- `auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/features/auth/login.component.ts`
- `frontend/src/app/features/auth/login.component.spec.ts`
- `frontend/src/app/features/account/account.component.spec.ts`
- `frontend/src/app/features/account/account.component.ts`
- `docs/mvp/iam/login/login-stab-03-multi-surface-login-logout.md`

Important review note: keep address-book files out of the MVP PR because buyer
address book is V2.

### Marketplace, Listings, Search, And Storefront

Likely MVP paths:

- `product-service/src/main/java/com/msb/ecom/product_service/config/SecurityConfig.java`
- `product-service/src/main/java/com/msb/ecom/product_service/controller/ListingController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/controller/ListingExceptionHandler.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/ListingDraftResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingDraftRepository.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingMediaRepository.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingModerationDecisionRepository.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/PublicListingSearchCriteria.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/AuthServiceClient.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/ListingService.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/PublicListingSearchRequests.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/RestAuthServiceClient.java`
- `product-service/src/main/resources/application.properties`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDomainFoundationMigrationTests.java`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
- `product-service/src/test/java/com/msb/ecom/product_service/search/OpenSearchListingSearchClientTests.java`
- `frontend/src/app/core/models/business-store.model.ts`
- `frontend/src/app/core/models/listing.model.ts`
- `frontend/src/app/core/services/business-store.service.spec.ts`
- `frontend/src/app/core/services/listing.service.ts`
- `frontend/src/app/core/services/listing.service.spec.ts`
- `frontend/src/app/features/listings/account-listings-entry.component.spec.ts`
- `frontend/src/app/features/listings/admin-listing-moderation-detail.component.ts`
- `frontend/src/app/features/listings/admin-listing-moderation-detail.component.spec.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.spec.ts`
- `frontend/src/app/features/listings/listing-draft-form.helpers.ts`
- `frontend/src/app/features/listings/listing-draft-form.helpers.spec.ts`
- `frontend/src/app/features/listings/listing-management.component.spec.ts`
- `frontend/src/app/features/marketplace/components/marketplace-navbar.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `frontend/src/app/features/stores/business-stores.component.ts`
- `frontend/src/app/features/stores/business-stores.component.spec.ts`
- `frontend/src/app/features/stores/public-store-profile.component.ts`
- `frontend/src/app/features/stores/public-store-profile.component.spec.ts`
- `frontend/src/app/testing/listing-test-fixtures.ts`
- `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
- `docs/mvp/search/search-01b-business-storefront-search.md`
- `docs/mvp/search/search-02a-individual-marketplace-keyword-search.md`
- `docs/mvp/search/search-05-search-state-and-visibility-hardening.md`

Important review note: keep product-service listing knowledge publication out
of the MVP PR unless the PR is explicitly the AI/RAG PR.

### Chat

Likely MVP paths:

- `chat-service/pom.xml`
- `chat-service/src/main/resources/application.properties`
- `frontend/src/app/core/services/chat.service.ts`
- `frontend/src/app/core/services/chat.service.spec.ts`
- `frontend/src/app/features/account/conversation-shell.component.ts`
- `frontend/src/app/features/account/conversation-shell.component.spec.ts`
- `frontend/src/app/features/chat/floating-chat.component.ts`
- `frontend/src/app/features/chat/floating-chat.component.spec.ts`

### Site Separation, Business, And Admin

Likely MVP paths:

- `frontend/src/app/features/business/business-account.component.spec.ts`
- `frontend/src/app/features/business/business-application.component.ts`
- `frontend/src/app/features/business/business-application.component.spec.ts`
- `frontend/src/app/features/business/business-store.component.spec.ts`
- `frontend/src/app/features/business/business-store-items.component.ts`
- `frontend/src/app/features/business/business-store-items.component.spec.ts`
- `frontend/src/app/features/admin/admin-access-denied.component.ts`
- `frontend/src/app/features/admin/admin-access-denied.component.spec.ts`
- `frontend/src/app/layout/admin-layout/admin-layout.component.ts`
- `frontend/src/app/layout/admin-layout/admin-layout.component.spec.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.ts`
- `frontend/src/app/layout/seller-layout/seller-layout.component.spec.ts`
- `docs/mvp/site/site-01-public-ui-surface-split.md`
- `docs/mvp/bus/bus-03-04-business-verification-and-admin-decision.md`
- `docs/mvp/bus/bus-list-00-store-item-self-publishing-plan.md`
- `docs/mvp/bus/bus-list-04-self-publish-to-stores.md`
- `docs/mvp/bus/bus-list-05-reactive-admin-removal.md`
- `docs/mvp/bus/bus-list-06-business-item-management-list.md`

Business portal work is MVP-adjacent. If the team wants a marketplace-only
review first, keep business portal changes in a second MVP PR.

## Exclude From MVP PR

Exclude these from the first MVP stabilization PR:

- `.env` and all `.env.*` local value changes.
- V2 cart, order, inventory, address-book, checkout, shipping, notification
  preparation.
- V3 AI/agent/RAG/embedding work.
- generated screenshots, browser artifacts, local logs, and local seed output.

Exact current-worktree exclusions:

- `.env`
- `.github/workflows/pull-request-quality.yml`
  - Current diff adds agent-service CI jobs, so keep it with the future AI PR.
- `AGENTS.md`
  - Current diff adds AI docs-folder guidance; keep it with the AI docs
    quarantine unless the team intentionally stages all docs organization
    updates together.
- `docker-compose.yml`
- `docker-compose.demo.yml`
- `run.sh`
  - Runtime/service-start changes are mixed and need a later runtime pass.
- `frontend/src/app/core/models/address.model.ts`
- `frontend/src/app/core/models/cart.model.ts`
- `frontend/src/app/core/models/inventory.model.ts`
- `frontend/src/app/core/services/address-book.service.ts`
- `frontend/src/app/core/services/address-book.service.spec.ts`
- `frontend/src/app/core/services/cart.service.ts`
- `frontend/src/app/core/services/cart.service.spec.ts`
- `frontend/src/app/core/services/inventory.service.ts`
- `frontend/src/app/core/services/inventory.service.spec.ts`
- `frontend/src/app/features/account/address-book.component.ts`
- `frontend/src/app/features/account/address-book.component.spec.ts`
- `frontend/src/app/features/business/business-inventory.component.ts`
- `frontend/src/app/features/business/business-inventory.component.spec.ts`
- `frontend/src/app/features/cart/cart.component.ts`
- `frontend/src/app/features/cart/cart.component.spec.ts`
- `inventory-service/`
- `order-service/`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AddressController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/InternalBusinessStoreCommerceController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/InternalBuyerAddressController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AddressCreateRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AddressPatchRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AddressResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/BusinessStoreCommerceEligibilityResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/InternalBuyerAddressResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/model/Address.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/repository/AddressRepository.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AddressBook*.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AddressNotFoundException.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AddressValidationException.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AddressVersionConflictException.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/BuyerAddressNotFoundException.java`
- `auth-service/src/main/resources/db/migration/identity/V202607181800__create_user_addresses.sql`
- `auth-service/src/main/resources/db/migration/identity/V202607191400__cascade_user_address_deletion.sql`
- `agent-service/`
- `docs/mvp/ai/`
- `docs/v2/commerce/`
- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalListingKnowledgeController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/knowledge/`
- `product-service/src/main/resources/db/migration/catalog/V202607160000__add_listing_publication_source.sql`
- `product-service/src/main/resources/db/migration/catalog/V202607180000__create_listing_knowledge_publication.sql`
- `product-service/src/test/java/com/msb/ecom/product_service/knowledge/`
- `tools/SeedTaihouUpload.java`
- `tools/seed_taihou_listings.py`
- `tools/update_taihou_listing_copy.py`

## Required Hunk-Staging Decisions

Use `git add --patch` for these files if a real commit is prepared:

```powershell
git add --patch pom.xml
git add --patch api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java
git add --patch frontend/src/app/app.routes.ts
git add --patch frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts
git add --patch frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
git add --patch docs/mvp/api-contract.md
git add --patch docs/mvp/database.md
git add --patch docs/mvp/development-roadmap.md
git add --patch docs/mvp/architecture.md
git add --patch docs/mvp/requirements.md
```

Observed hunk choices:

- `pom.xml`
  - Stage: Surefire `net.bytebuddy.experimental=true`.
  - Do not stage: `inventory-service` and `order-service` module entries.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`
  - Stage: fallback method support only if it is needed by included MVP
    routes.
  - Do not stage: cart, inventory, and address-book route hunks.
- `frontend/src/app/app.routes.ts`
  - Stage: MVP marketplace/account/admin/storefront route hunks.
  - Do not stage: `/cart` and seller inventory route hunks.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
  - Stage: signed-out confirmation/logout-session hunks only.
  - Do not stage: cart count, cart load, and cart reset hunks.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
  - Stage: chat test double and logout/session expectations needed by MVP.
  - Do not stage: cart-specific test setup or assertions.
- `docs/mvp/api-contract.md`
  - Stage: MVP auth/logout, listing, search, storefront, admin, and chat
    contract corrections.
  - Do not stage: V2 address-book, cart, inventory, order, checkout, or AI
    contract expansions.
- `docs/mvp/development-roadmap.md`
  - Stage: cleanup references and MVP stabilization truth.
  - Do not stage: V2/V3 completion claims in the MVP PR.
- `docs/mvp/database.md`, `docs/mvp/architecture.md`, `docs/mvp/requirements.md`
  - Stage only MVP release-boundary corrections.
  - Do not stage V2 commerce or V3 AI expansions.

## Actual Next Staging Command Shape

When staging starts, first stage the pure documentation files:

```powershell
git add docs/mvp/fix/stabilization/post-search/cleanup-p0-worktree-boundary-and-verification.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-staging-and-quarantine-plan.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-01a-mvp-stage-dry-run.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-02-v2-quarantine-checklist.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-03-ai-quarantine-checklist.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-04-env-cleanup-review.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-05-teammate-ready-pr-scope.md
```

Then hunk-stage mixed tracked files with the decisions above.

Do not run `git add .`.

## Verification Commands

Before opening the MVP PR, run:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false
npm.cmd run build
cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

Do not use the full Maven reactor for the MVP PR while `inventory-service` and
`order-service` are intentionally quarantined.

## Acceptance Criteria

- The MVP PR can be reviewed without cart, inventory, order, address-book, or
  AI files.
- The MVP frontend and backend verification commands pass.
- No environment file is staged.
- Mixed files have only MVP hunks staged.
