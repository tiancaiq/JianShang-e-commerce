# CLEAN-P1-05 Teammate-Ready PR Scope

Status: Complete  
Date: 2026-07-19

## Goal

Define the smallest reviewable PR that teammates can safely build on.

This scope does not stage files, delete files, edit environment files, or
change application behavior.

## Implementation Result

The current worktree was inspected with:

```powershell
git status --short --untracked-files=all
```

Result: the worktree contains MVP, V2, V3/AI, environment, runtime, and local
artifact work at the same time. A teammate-ready PR must be assembled by
selective staging, not by whole-worktree staging.

No files were staged during this cleanup. No environment files were edited.

## Recommended PR Name

`MVP stabilization baseline after marketplace/search/profile work`

## Include

- POSTSEARCH-STAB-P0 verification document and fixes.
- MVP auth/session/profile/logout stability.
- MVP public marketplace browse/search/detail.
- MVP listing management and moderation fixes.
- MVP basic buyer/seller chat behavior.
- MVP site separation docs and route cleanup.
- MVP docs that describe the active release target.

Concrete include groups:

- cleanup docs under
  `docs/mvp/fix/stabilization/post-search/cleanup-p*.md`
- MVP auth/session/login/profile files listed in
  `cleanup-p1-01a-mvp-stage-dry-run.md`
- MVP listing/search/storefront files listed in
  `cleanup-p1-01a-mvp-stage-dry-run.md`
- MVP chat files listed in
  `cleanup-p1-01a-mvp-stage-dry-run.md`
- MVP admin/business/site-separation files listed in
  `cleanup-p1-01a-mvp-stage-dry-run.md`
- `docs/mvp/development-roadmap.md` hunks that reference the cleanup docs and
  preserve the active MVP boundary.

## Exclude

- `.env` and env-file changes.
- V2 cart, order, inventory, address-book, checkout, shipping, or notification
  work.
- V3 AI, agent-service, RAG, embedding, or OpenAI provider work.
- local browser/test artifacts.
- broad refactors not needed for the MVP baseline.

Concrete exclude groups:

- all files listed in
  `cleanup-p1-02-v2-quarantine-checklist.md`
- all files listed in
  `cleanup-p1-03-ai-quarantine-checklist.md`
- all env files covered by
  `cleanup-p1-04-env-cleanup-review.md`
- `.github/workflows/pull-request-quality.yml` while its diff is AI-only.
- `AGENTS.md` while its diff is AI-docs-only.
- `docker-compose.yml`, `docker-compose.demo.yml`, and `run.sh` until a
  focused runtime/devex PR separates MVP runtime changes from V2/AI changes.
- `tools/SeedTaihouUpload.java`
- `tools/seed_taihou_listings.py`
- `tools/update_taihou_listing_copy.py`

## Pre-PR Checklist

1. Run `git status --short --untracked-files=all`.
2. Confirm no env files are staged.
3. Hunk-stage mixed files listed in
   `cleanup-p1-01a-mvp-stage-dry-run.md`.
4. Run frontend tests.
5. Run frontend build.
6. Run Maven tests for the MVP services.
7. Open the PR with a note that V2 and AI work are intentionally parked.

Detailed staging order:

1. Stage pure cleanup docs:

```powershell
git add docs/mvp/fix/stabilization/post-search/cleanup-p0-worktree-boundary-and-verification.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-staging-and-quarantine-plan.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-01a-mvp-stage-dry-run.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-02-v2-quarantine-checklist.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-03-ai-quarantine-checklist.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-04-env-cleanup-review.md
git add docs/mvp/fix/stabilization/post-search/cleanup-p1-05-teammate-ready-pr-scope.md
```

2. Hunk-stage mixed files. Do not stage whole files unless every hunk belongs
   to MVP:

```powershell
git add --patch .gitignore
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

3. Stage MVP-only files after reviewing them against the dry-run doc:

```powershell
git add api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java
git add api-gateway/src/test/java/com/msb/ecom/api_gateway/ApiGatewayApplicationTests.java
git add api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java
git add auth-service/pom.xml
git add auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthServiceExceptionHandler.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/controller/PublicIdentityLabelController.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/dto/BusinessIdentityLabelResponse.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/dto/BusinessStoreResponse.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/repository/UserRepository.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/service/AdminAuthorizationService.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/service/BusinessMembershipService.java
git add auth-service/src/main/java/com/msb/ecom/auth_service/service/BusinessStoreService.java
git add auth-service/src/main/resources/application.properties
git add auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java
git add chat-service/pom.xml
git add chat-service/src/main/resources/application.properties
```

4. Stage frontend MVP-only files after reviewing that cart/address/inventory
   imports are not included:

```powershell
git add frontend/src/app/app.config.ts
git add frontend/src/app/app.routes.spec.ts
git add frontend/src/app/core/guards/auth.guard.spec.ts
git add frontend/src/app/core/models/business-store.model.ts
git add frontend/src/app/core/models/listing.model.ts
git add frontend/src/app/core/services/auth.service.ts
git add frontend/src/app/core/services/auth.service.spec.ts
git add frontend/src/app/core/services/business-store.service.spec.ts
git add frontend/src/app/core/services/chat.service.ts
git add frontend/src/app/core/services/chat.service.spec.ts
git add frontend/src/app/core/services/listing.service.ts
git add frontend/src/app/core/services/listing.service.spec.ts
git add frontend/src/app/features/account/account.component.ts
git add frontend/src/app/features/account/account.component.spec.ts
git add frontend/src/app/features/account/conversation-shell.component.ts
git add frontend/src/app/features/account/conversation-shell.component.spec.ts
git add frontend/src/app/features/admin/admin-access-denied.component.ts
git add frontend/src/app/features/auth/login.component.ts
git add frontend/src/app/features/auth/login.component.spec.ts
git add frontend/src/app/features/business/business-account.component.spec.ts
git add frontend/src/app/features/business/business-application.component.ts
git add frontend/src/app/features/business/business-application.component.spec.ts
git add frontend/src/app/features/business/business-store-items.component.ts
git add frontend/src/app/features/business/business-store-items.component.spec.ts
git add frontend/src/app/features/business/business-store.component.spec.ts
git add frontend/src/app/features/chat/floating-chat.component.ts
git add frontend/src/app/features/chat/floating-chat.component.spec.ts
git add frontend/src/app/features/listings/account-listings-entry.component.spec.ts
git add frontend/src/app/features/listings/admin-listing-moderation-detail.component.ts
git add frontend/src/app/features/listings/admin-listing-moderation-detail.component.spec.ts
git add frontend/src/app/features/listings/listing-draft-form.component.ts
git add frontend/src/app/features/listings/listing-draft-form.component.spec.ts
git add frontend/src/app/features/listings/listing-draft-form.helpers.ts
git add frontend/src/app/features/listings/listing-draft-form.helpers.spec.ts
git add frontend/src/app/features/listings/listing-management.component.spec.ts
git add frontend/src/app/features/marketplace/components/marketplace-navbar.component.ts
git add frontend/src/app/features/marketplace/marketplace-home.component.ts
git add frontend/src/app/features/marketplace/marketplace-home.component.spec.ts
git add frontend/src/app/features/marketplace/public-listing-detail.component.ts
git add frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts
git add frontend/src/app/features/stores/business-stores.component.ts
git add frontend/src/app/features/stores/business-stores.component.spec.ts
git add frontend/src/app/features/stores/public-store-profile.component.ts
git add frontend/src/app/features/stores/public-store-profile.component.spec.ts
git add frontend/src/app/layout/admin-layout/admin-layout.component.ts
git add frontend/src/app/layout/admin-layout/admin-layout.component.spec.ts
git add frontend/src/app/layout/seller-layout/seller-layout.component.ts
git add frontend/src/app/layout/seller-layout/seller-layout.component.spec.ts
git add frontend/src/app/testing/listing-test-fixtures.ts
git add frontend/src/environments/environment.development.ts
git add frontend/src/environments/environment.ts
```

5. Stage product-service MVP-only files after excluding AI knowledge files:

```powershell
git add product-service/src/main/java/com/msb/ecom/product_service/config/SecurityConfig.java
git add product-service/src/main/java/com/msb/ecom/product_service/controller/ListingController.java
git add product-service/src/main/java/com/msb/ecom/product_service/controller/ListingExceptionHandler.java
git add product-service/src/main/java/com/msb/ecom/product_service/dto/ListingDraftResponse.java
git add product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingResponse.java
git add product-service/src/main/java/com/msb/ecom/product_service/repository/ListingDraftRepository.java
git add product-service/src/main/java/com/msb/ecom/product_service/repository/ListingMediaRepository.java
git add product-service/src/main/java/com/msb/ecom/product_service/repository/ListingModerationDecisionRepository.java
git add product-service/src/main/java/com/msb/ecom/product_service/repository/PublicListingSearchCriteria.java
git add product-service/src/main/java/com/msb/ecom/product_service/service/AuthServiceClient.java
git add product-service/src/main/java/com/msb/ecom/product_service/service/ListingService.java
git add product-service/src/main/java/com/msb/ecom/product_service/service/PublicListingSearchRequests.java
git add product-service/src/main/java/com/msb/ecom/product_service/service/RestAuthServiceClient.java
git add product-service/src/main/resources/application.properties
git add product-service/src/test/java/com/msb/ecom/product_service/ListingDomainFoundationMigrationTests.java
git add product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java
git add product-service/src/test/java/com/msb/ecom/product_service/search/OpenSearchListingSearchClientTests.java
```

6. Confirm no quarantined files are staged:

```powershell
git diff --cached --name-only
git diff --cached -- .env .env.dev .env.example .env.demo.example
git diff --cached -- agent-service docs/mvp/ai docs/v2/commerce inventory-service order-service
```

Do not run `git add .`.

## Verification Commands

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false
npm.cmd run build
cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

## Reviewer Notes

- The full repository worktree is intentionally larger than this PR because V2
  and AI work already exist locally.
- Review should focus on whether the MVP can run without V2 and AI services.
- Any future commerce PR should start from the V2 quarantine checklist.
- Any future AI PR should start from the AI quarantine checklist.
- Runtime files are intentionally not included in the MVP stabilization PR
  until V2/AI/runtime concerns are separated.
- `.env` is intentionally not included. Local configuration should stay local.

## Acceptance Criteria

- The MVP PR has one clear purpose.
- Teammates can review and run it without understanding parked V2/V3 work.
- The PR does not contain secrets or local environment values.
- The PR can be built and tested with the MVP verification commands only.
