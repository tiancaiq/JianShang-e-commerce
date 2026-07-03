# POSTSEARCH-STAB-P1-05 Shared Object Storage Upload Abstraction

Status: complete.

## Goal

Reduce drift between listing media uploads and user avatar uploads by sharing
the equivalent S3-compatible object-storage plumbing.

## Decision

Create a new backend Maven module: `common-storage`.

Why:

- The AWS SDK dependency does not belong in `common-core`.
- The behavior is technical storage plumbing, not a runtime service.
- Both `auth-service` and `product-service` already need equivalent S3/GCS
  operations.

## Shared Technical Operations

`common-storage` owns:

- S3-compatible client and presigner construction
- presigned PUT upload target creation
- direct object upload
- object metadata verification by size and content type
- object read through a presigned GET URL
- object delete-if-exists
- local-demo upload target URI creation

## Service-Owned Rules

`product-service` still owns listing media rules:

- listing media object keys
- allowed listing media metadata
- listing ownership and seller authorization
- listing image attachment and moderation state
- public listing media exception mapping

`auth-service` still owns avatar rules:

- avatar allowed content types
- avatar size limit
- user-specific avatar object keys
- profile versioning
- avatar URL update/delete behavior
- public avatar visibility rules

## Files Changed

- `pom.xml`
- `common-storage/pom.xml`
- `common-storage/src/main/java/com/msb/ecom/common/storage/object/*`
- `common-storage/src/test/java/com/msb/ecom/common/storage/*`
- `product-service/pom.xml`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/S3ListingMediaStorage.java`
- `product-service/src/main/java/com/msb/ecom/product_service/storage/LocalDemoListingMediaStorage.java`
- `auth-service/pom.xml`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/S3AvatarStorageService.java`
- `AGENTS.md`
- `docs/mvp/architecture.md`
- `docs/mvp/fix/stabilization/post-search/fix-05-post-search-profile-stabilization-sprint.md`

## Behavior

No product behavior change is intended.

- Existing listing media API contracts are unchanged.
- Existing avatar API contracts are unchanged.
- Storage configuration property names are unchanged.
- No database migrations were added.
- No new storage provider behavior was added.

## Verification

Initial sandboxed Maven run failed before compilation because network access was
blocked while resolving Maven artifacts:

```powershell
.\mvn.cmd -pl common-storage -am test
```

Passed after rerun with normal Maven dependency access:

```powershell
.\mvn.cmd -pl common-storage -am test
```

Result: common-storage 2 tests passed.

Passed:

```powershell
.\mvn.cmd -pl auth-service,product-service -am test
```

Result:

- `common-core`: 11 tests passed
- `common-web`: 16 tests passed
- `common-storage`: 2 tests passed
- `common-testing`: 3 tests passed
- `product-service`: 105 tests passed
- `auth-service`: 61 tests passed

## Non-Goals

- No runtime `common-service`.
- No controller, repository, JPA entity, migration, or business workflow moved
  into shared code.
- No change to object-key ownership rules.
- No change to authorization or public URL rules.
- No GCS-specific client behavior beyond the existing S3-compatible path.
