# POSTSEARCH-STAB-P0-01 Verification And Git Baseline Refresh

Status: complete.

## Goal

Create a clean verification baseline after the profile/avatar and
search/storefront work, without changing product behavior.

This slice did not delete files, refactor code, modify production behavior, or
add features.

## Git Status Review

Command:

```powershell
git status --short --untracked-files=all
```

The working tree is intentionally large and currently spans environment,
gateway, auth, product, frontend, Docker Compose, and documentation files.

### Intentional Work Areas

Profile and avatar work:

- gateway avatar upload proxy wiring
- auth-service avatar upload, confirmation, public avatar delivery, and local
  or S3-compatible storage support
- frontend account dashboard, profile card, profile edit, and avatar display
- user/profile/avatar slice documentation

Search and storefront work:

- product-service public listing search DTOs, criteria, OpenSearch projection,
  MySQL visibility revalidation, and tests
- frontend listing service, marketplace browse/search, business storefront
  search, and related tests
- search slice documentation for business storefront search, shared cursor
  pagination, and OpenSearch projection

Roadmap and planning work:

- updated MVP API contract and roadmap documents
- new post-search/profile stabilization sprint plan
- updated active/deferred documentation references

Environment and deployment wiring:

- tracked `.env`, `.env.example`, `.env.demo.example`
- `docker-compose.yml`, `docker-compose.demo.yml`, `docker-compose.prod.yml`
- service application property changes for auth, gateway, product, storage, and
  search configuration

### Needs Follow-Up

These are not changed in this slice, but they should be handled before more
feature work:

- `.env` is tracked and modified, so `POSTSEARCH-STAB-P0-02` must audit it for
  real secrets before any PR is opened.
- `docs/mvp/search/search-01b-business-storefront-search.md` is the current
  business storefront search source of truth. `POSTSEARCH-STAB-P0-04` should
  verify references and source-of-truth naming.
- Several new slice docs are untracked. They appear intentional, but should be
  included or deliberately deferred when preparing the PR.

## Generated And Local Artifact Check

Commands:

```powershell
git status --short --ignored -- frontend\dist frontend\node_modules frontend\.angular frontend\ng-serve*.log auth-service\target product-service\target api-gateway\target common-core\target common-web\target common-testing\target
git status --short --ignored --untracked-files=all | rg -i "upload|avatar|media|storage|bucket|gcs|s3|dist|target|node_modules|\.angular|ng-serve"
git ls-files | rg -i "(^|/)(dist|target|node_modules|\.angular)(/|$)|ng-serve.*\.log|upload|avatar|media|storage|bucket|gcs|s3"
```

Result:

- `frontend/dist/`, `frontend/node_modules/`, `frontend/.angular/`, and
  `frontend/ng-serve*.log` are ignored.
- Maven `target/` directories for gateway, auth-service, product-service, and
  common modules are ignored.
- IDE data-source cache under `.idea/dataSources/.../storage_v2/` is ignored.
- No generated frontend build output, Node dependencies, Maven target output,
  or local upload artifact path was found as a tracked file.
- The tracked storage/media/avatar matches are source code and documentation,
  not generated upload objects.

## Verification Results

### Frontend Tests

Command:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Result: passed.

- `TOTAL: 224 SUCCESS`
- The first sandboxed attempt failed because the test runner could not read
  required dependency paths from the restricted sandbox. The elevated rerun
  passed.
- Test output included expected local web-server `404` warnings for mocked
  avatar/listing image URLs. They did not fail the suite.

### Frontend Build

Command:

```powershell
cd frontend
npm.cmd run build
```

Result: passed.

Angular emitted component CSS budget warnings for:

- `marketplace-layout.component.ts`
- `profile.component.ts`
- `business-stores.component.ts`
- `marketplace-home.component.ts`
- `individual-seller-activation.component.ts`

These warnings are not blocking for this baseline. They are good input for the
planned frontend responsibility/style cleanup slices.

### Backend Tests

Command:

```powershell
C:\Users\b\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd -pl api-gateway,auth-service,product-service -am test
```

Result: passed.

Reactor summary:

- `common-core`: success
- `common-web`: success
- `common-testing`: success
- `product-service`: success
- `api-gateway`: success
- `auth-service`: success

Notes:

- The product-service suite includes the current search/OpenSearch-focused
  tests.
- Testcontainers used the local Docker Desktop engine successfully.
- Maven output included non-blocking JDK/Flyway/Mockito deprecation warnings.

## Acceptance Criteria

- Verification result is documented: yes.
- Git state has no accidental generated artifacts: yes.
- Test failures are not present after elevated verification reruns: yes.
- Product behavior changes in this slice: none.

## Deferred Follow-Up

- `POSTSEARCH-STAB-P0-02`: audit auth/session/avatar/storage security and
  tracked environment files.
- `POSTSEARCH-STAB-P0-03`: audit public search and storefront contracts.
- `POSTSEARCH-STAB-P0-04`: clean source-of-truth docs and roadmap drift.
- `POSTSEARCH-STAB-P0-05`: verify OpenSearch operational safety.
