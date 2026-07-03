# STAB-P0-01 Git And Verification Baseline

Status: complete.

## Goal

Create a teammate-ready baseline report before continuing MVP feature
development.

This slice did not change product behavior, delete code, refactor code, or add
features.

## Git Status Review

Command:

```powershell
git status --short --untracked-files=all
```

Result:

- Working tree is not committed yet.
- No accidental generated build output was visible in Git status after
  frontend and backend verification.
- Ignored build outputs such as `frontend/dist`, Angular cache, Maven
  `target`, and dependency folders remained ignored.
- No files were deleted.

## Intentional Untracked Files

These untracked files appear intentional and should be reviewed/staged with
their related MVP work:

Docs:

- `docs/mvp/fix/stabilization/general/fix-02-stabilization-sprint-plan.md`
- `docs/mvp/ind/ind-03-marketplace-individual-selling-plan.md`
- `docs/mvp/list/list-07-public-listing-detail.md`
- `docs/mvp/list/media-01-object-storage-image-delivery.md`
- `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
- `docs/mvp/search/search-01-public-approved-listing-browse.md`
- `docs/mvp/ui/marketplace-ui-redesign.md`

Frontend:

- `frontend/src/app/features/listings/listing-management.component.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.ts`

Backend:

- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingImageResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/ListingMediaStorage.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/ListingMediaStorageConfig.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/ListingMediaStorageProperties.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/LocalDemoListingMediaStorage.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/S3ListingMediaStorage.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/StorageUploadTarget.java`
- `product-service/src/main/resources/db/migration/catalog/V202606170600__backfill_listing_published_at.sql`

## Accidental Untracked Files

None found.

No cleanup deletion was performed because the visible untracked files map to
completed or planned MVP work.

## Tracked Modified Files

Tracked modified files remain from prior MVP work and documentation updates.
They were not reverted or edited by this slice except for documentation that
records the STAB-P0-01 result.

Main groups:

- MVP product docs and agent instructions.
- Marketplace/admin/seller route and layout files.
- Listing form, listing management, marketplace, and moderation UI files.
- Frontend route/listing tests.

## Verification Results

### Frontend Tests

Command:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Working directory:

```text
frontend
```

Result:

```text
TOTAL: 84 SUCCESS
```

Note:

- The first sandboxed attempt failed before tests ran because Angular/esbuild
  could not read required workspace and `node_modules` paths.
- The same command passed when rerun with normal filesystem access.

### Frontend Build

Command:

```powershell
npm.cmd run build
```

Working directory:

```text
frontend
```

Result:

```text
Application bundle generation complete.
Output location: C:\Users\b\IdeaProjects\msb-ecom\frontend\dist\frontend
```

### Backend Tests

Command:

```powershell
.\mvnw.cmd test
```

Working directory:

```text
C:\Users\b\IdeaProjects\msb-ecom
```

Result:

```text
BUILD SUCCESS
Total time: 02:17 min
```

Surefire report summary:

```text
tests=137 failures=0 errors=0 skipped=0
```

Reactor modules:

- `msb-ecom`
- `common-core`
- `common-web`
- `common-testing`
- `product-service`
- `order-service`
- `inventory-service`
- `api-gateway`
- `notification-service`
- `payment-service`
- `auth-service`

Warnings observed:

- Flyway recommends upgrading for MySQL 8.4 support.
- Mockito dynamic agent loading warns about future JDK behavior.
- Some JVM/native-access warnings appear under the current local Java runtime.
- A common-web test intentionally logs a fake `password=top-secret` exception
  while asserting safe error envelopes; this is test data, not a real secret.

## Acceptance Notes

- Git status was reviewed.
- Intentional and accidental untracked files were classified.
- No code was deleted.
- Frontend tests passed.
- Frontend build passed.
- Backend tests passed with Docker/Testcontainers available.
- No product behavior changed.

## Next Cleanup Slice

Proceed to `STAB-P0-02`: remove individual seller tools from the business
seller portal while keeping marketplace account routes for individual sellers.
