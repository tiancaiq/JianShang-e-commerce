# POSTSEARCH-STAB-P1-04 Regression Test Organization

Status: complete.

## Goal

Make completed MVP regression coverage easier for teammates to find by product
flow instead of implementation history.

## Scope

This cleanup covers test organization and one missing no-behavior regression
for completed business storefront search pagination.

## Product Flow Test Index

### Auth And Session

Primary backend specs:

- `api-gateway/src/test/java/com/msb/ecom/api_gateway/auth/AuthBffControllerNativeTests.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/auth/NativeAuthServiceTests.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java`

Primary frontend specs:

- `frontend/src/app/features/auth/login.component.spec.ts`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.spec.ts`

Focused command:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerNativeTests,NativeAuthServiceTests,AuthBffControllerTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/auth/login.component.spec.ts --include src/app/core/services/auth.service.spec.ts --include src/app/core/guards/auth.guard.spec.ts --include src/app/core/interceptors/auth.interceptor.spec.ts
```

### Profile And Avatar

Primary backend specs:

- `auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java`

Primary frontend specs:

- `frontend/src/app/features/account/profile.component.spec.ts`
- `frontend/src/app/core/services/user-profile.service.spec.ts`
- `frontend/src/app/features/account/user-profile-card.component.spec.ts`
- `frontend/src/app/features/account/account-dashboard.component.spec.ts`

Focused command:

```powershell
.\mvnw.cmd -pl auth-service -am test "-Dtest=AuthServiceApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/account/profile.component.spec.ts --include src/app/core/services/user-profile.service.spec.ts --include src/app/features/account/user-profile-card.component.spec.ts --include src/app/features/account/account-dashboard.component.spec.ts
```

### Listings And Media

Primary backend specs:

- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDomainFoundationMigrationTests.java`

Primary frontend specs:

- `frontend/src/app/features/listings/listing-draft-form.component.spec.ts`
- `frontend/src/app/features/listings/listing-draft-form.helpers.spec.ts`
- `frontend/src/app/features/listings/listing-media-upload.service.spec.ts`
- `frontend/src/app/features/listings/listing-management.component.spec.ts`
- `frontend/src/app/features/listings/account-listings-entry.component.spec.ts`

Focused command:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests,ListingDomainFoundationMigrationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/listings/listing-draft-form.component.spec.ts --include src/app/features/listings/listing-draft-form.helpers.spec.ts --include src/app/features/listings/listing-media-upload.service.spec.ts --include src/app/features/listings/listing-management.component.spec.ts --include src/app/features/listings/account-listings-entry.component.spec.ts
```

### Public Search And Storefront

Primary backend specs:

- `product-service/src/test/java/com/msb/ecom/product_service/search/OpenSearchListingSearchClientTests.java`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`

Primary frontend specs:

- `frontend/src/app/core/services/listing.service.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `frontend/src/app/features/stores/business-stores.component.spec.ts`
- `frontend/src/app/shared/listing/public-listing-display.spec.ts`

Focused command:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests,OpenSearchListingSearchClientTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/core/services/listing.service.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts --include src/app/features/marketplace/public-listing-detail.component.spec.ts --include src/app/features/stores/business-stores.component.spec.ts --include src/app/shared/listing/public-listing-display.spec.ts
```

### Admin Moderation

Primary backend specs:

- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
- `auth-service/src/test/java/com/msb/ecom/auth_service/controller/BusinessApplicationVersionHeaderTest.java`

Primary frontend specs:

- `frontend/src/app/features/listings/admin-listing-moderation.component.spec.ts`
- `frontend/src/app/features/listings/admin-listing-moderation-detail.component.spec.ts`
- `frontend/src/app/features/business/admin-business-application-queue.component.spec.ts`
- `frontend/src/app/features/business/admin-business-application-detail.component.spec.ts`
- `frontend/src/app/features/business/admin-business-application-decision.component.spec.ts`
- `frontend/src/app/core/guards/admin.guard.spec.ts`

Focused command:

```powershell
.\mvnw.cmd -pl auth-service,product-service -am test "-Dtest=BusinessApplicationVersionHeaderTest,ListingDraftApiTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/listings/admin-listing-moderation.component.spec.ts --include src/app/features/listings/admin-listing-moderation-detail.component.spec.ts --include src/app/features/business/admin-business-application-queue.component.spec.ts --include src/app/features/business/admin-business-application-detail.component.spec.ts --include src/app/features/business/admin-business-application-decision.component.spec.ts --include src/app/core/guards/admin.guard.spec.ts
```

## Changes

- Renamed selected frontend spec suites around product flows:
  - marketplace public browse
  - business storefront
  - profile/avatar
  - listing API client
- Added a missing frontend regression that confirms business storefront search
  sends `cursor` and `limit` when loading another page.
- Documented focused backend and frontend commands for major MVP areas.

## Behavior

No product behavior changed.

- No endpoint paths changed.
- No JSON fields changed.
- No database migrations changed.
- No UI behavior changed.

## Verification

Passed:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false --include src/app/core/services/listing.service.spec.ts --include src/app/core/services/user-profile.service.spec.ts --include src/app/features/account/profile.component.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts --include src/app/features/stores/business-stores.component.spec.ts
```

Result: 58 specs passed. The run emitted expected test-server 404 warnings for
mock listing-media and user-avatar URLs, but no failures.

## Non-Goals

- No production refactor.
- No new product behavior.
- No full E2E or visual regression framework.
- No backend test class split in this slice.
