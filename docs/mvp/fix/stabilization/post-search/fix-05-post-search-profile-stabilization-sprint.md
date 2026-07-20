# FIX-05 Post-Search And Profile Stabilization Sprint

Status: in progress.

## Scope

This stabilization sprint reviews completed MVP work after the user
profile/avatar and search/storefront slices.

Completed scope to review:

- IAM-00 through IAM-06
- LOGIN-01 and LOGIN-02
- SIGNUP-00 through SIGNUP-05
- USER-00 through USER-05
- USER-STAB-01
- IND-01 and IND-02
- BUS-01 through BUS-04
- LIST-00 through LIST-07
- MEDIA-01
- LIST frontend stabilization
- SITE-00 and SITE-01
- SEARCH-00
- SEARCH-01 public browse
- SEARCH-01A individual marketplace search
- SEARCH-01B business storefront search
- SEARCH-03 shared filters, sorting, and cursor pagination
- SEARCH-04 OpenSearch projection

Do not add product features in this sprint. The goal is to make completed
auth/profile/avatar/listing/search work safe for teammates before starting
chat, richer admin workflows, or more marketplace account features.

## Stabilization Rules

Every cleanup slice must:

- be one pull request
- preserve current user-visible behavior
- preserve API contracts unless the PR is explicitly a documentation-only
  contract correction
- preserve database schema unless a verified data-safety issue requires a
  forward-safe migration
- avoid new chat, checkout, cart, inventory, payment, order, shipping,
  notification, review, reputation, trade-completion, or AI behavior
- not expose raw object storage URLs, object keys, tokens, secrets, private
  contact data, or exact individual locations
- include verification notes in the PR
- update documentation only when it clarifies completed behavior or removes
  source-of-truth drift

## Review Summary

The completed MVP work now covers profile/account UX, avatar upload and public
avatar delivery, split marketplace/store search, cursor pagination, and an
OpenSearch-derived projection. This is good progress, but the risk profile
changed.

Main risks before the next feature area:

- The working tree contains many modified and untracked files across auth,
  gateway, product, frontend, compose, environment, and docs.
- `.env` files are modified and avatar/listing storage uses S3-compatible
  credentials, so secret hygiene must be checked again.
- Docs currently contain old top-level slice files, newer foldered slice docs,
  multiple stabilization plans, and roadmap status drift around `SEARCH-04`.
- Avatar upload crosses gateway, auth-service, browser direct upload, storage,
  public image delivery, and frontend image binding.
- Public search now has two split surfaces plus OpenSearch fallback behavior;
  visibility revalidation must remain MySQL-backed.
- Frontend marketplace/account/search components have grown and may become
  hard to review before chat or account features are added.

## P0: Must Fix Before More Feature Work

### POSTSEARCH-STAB-P0-01 Verification And Git Baseline Refresh

Status: complete.

Verification note:

- `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-01-verification-git-baseline-refresh.md`

One PR: yes.

Problem:

- Completed profile/avatar/search/OpenSearch work spans multiple services,
  frontend routes, environment files, Docker compose files, and docs.
- Teammates need one known-good baseline before starting chat or more
  marketplace features.

Likely files:

- new verification note under `docs/mvp/fix/`
- no production code unless verification exposes a blocking bug

Tasks:

- Review `git status --short --untracked-files=all`.
- Classify intentional versus accidental changed/untracked files.
- Confirm no generated build output or local-only upload files are tracked.
- Run frontend test/build.
- Run backend tests for active MVP services.
- Run search/OpenSearch-focused tests if they are separate from the normal
  product-service suite.
- Document any local Docker/Testcontainers or OpenSearch limitation.

Tests:

```powershell
git status --short --untracked-files=all
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
cd ..
.\mvnw.cmd -pl api-gateway,auth-service,product-service -am test
```

Acceptance criteria:

- Verification result is documented.
- Git state has no accidental generated artifacts.
- Any test failure is either fixed in the slice or explicitly split into a
  named blocking bug slice.
- No product behavior changes.

### POSTSEARCH-STAB-P0-02 Auth, Session, Avatar, And Storage Security Audit

Status: complete.

Verification note:

- `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-02-auth-session-avatar-storage-security-audit.md`

One PR: yes.

Problem:

- Native login/register, Google provider wiring, avatar signed upload, public
  avatar delivery, and object storage configuration touch sensitive security
  boundaries.
- This must be checked before chat introduces more user-to-user exposure.

Likely files:

- `.env*`
- `docker-compose*.yml`
- `api-gateway/src/main/java/...`
- `auth-service/src/main/java/...`
- `auth-service/src/main/resources/application*.properties`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/user-profile.service.ts`
- `frontend/src/app/features/account/*`
- `docs/mvp/api-contract.md`
- `docs/mvp/iam/user-profile/user-05-avatar-image-upload.md`

Tasks:

- Confirm browser JavaScript still never receives access, refresh, or ID
  tokens.
- Confirm state-changing browser auth/profile/avatar requests use CSRF where
  applicable.
- Confirm remote signed avatar uploads do not send gateway cookies, CSRF
  headers, or unrelated custom headers.
- Confirm public avatar reads do not expose raw storage paths, object keys,
  buckets, signed URLs, email, phone, Keycloak subject, or role data.
- Search `.env*`, compose, docs, and properties for real storage credentials
  or provider secrets.
- Confirm app-owned avatar URLs remain accepted by `PATCH /users/me`.

Tests:

```powershell
rg -n "ACCESS_KEY|SECRET|TOKEN|GOOG|GCS|S3|storage.googleapis.com|avatar|csrf|refresh|id_token|access_token" .env* docker-compose*.yml docs api-gateway auth-service frontend/src/app
.\mvnw.cmd -pl api-gateway,auth-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/account/profile.component.spec.ts --include src/app/features/account/user-profile-card.component.spec.ts --include src/app/core/services/auth.service.spec.ts --include src/app/core/services/user-profile.service.spec.ts
```

Acceptance criteria:

- No real shared secrets are committed or documented as defaults.
- Auth/session/token boundaries still match ADR-0001 and the API contract.
- Avatar upload and public delivery remain app-owned and private-data safe.
- No new profile fields, identity provider behavior, or storage provider
  behavior is added.

### POSTSEARCH-STAB-P0-03 Public Search And Storefront Contract Audit

Status: complete.

One PR: yes.

Problem:

- Marketplace search, business storefront search, shared filters, cursor
  pagination, and OpenSearch projection are now implemented.
- The split surfaces must not collapse into a generic search path or leak
  unsafe listing/seller data.

Likely files:

- `product-service/src/main/java/.../controller/ListingController.java`
- `product-service/src/main/java/.../service/ListingService.java`
- `product-service/src/main/java/.../repository/ListingDraftRepository.java`
- `product-service/src/main/java/.../search/*`
- `product-service/src/test/java/...`
- `frontend/src/app/core/services/listing.service.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/stores/business-stores.component.ts`
- `docs/mvp/api-contract.md`
- `docs/mvp/search/*`

Tasks:

- Verify individual marketplace search returns only approved active
  `INDIVIDUAL` listings.
- Verify business storefront search returns only approved active `BUSINESS`
  listings.
- Verify cursor pagination is deterministic for `newest`, `price_asc`, and
  `price_desc`.
- Verify frontend `Load more` appends without duplicating or replacing
  existing cards incorrectly.
- Verify OpenSearch candidate IDs are reloaded from MySQL before response.
- Verify disabled/unavailable OpenSearch behavior matches the documented
  MySQL default and `503 LISTING_SEARCH_UNAVAILABLE` rule when enabled.
- Confirm public cards/details hide owner private IDs, email, phone, exact
  location, moderation internals, storage object keys, and checkout language.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/core/services/listing.service.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts --include src/app/features/stores/business-stores.component.spec.ts
```

Acceptance criteria:

- Search contract behavior is covered by focused tests or documented as an
  explicit test gap.
- OpenSearch remains a derived projection, never the authority for public
  visibility.
- No public route introduces cart, checkout, payment, order, shipping,
  inventory, trade completion, review, or AI behavior.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-03-public-search-storefront-contract-audit.md`.

### POSTSEARCH-STAB-P0-04 Source-Of-Truth Documentation And Roadmap Cleanup

Status: complete.

One PR: yes.

Problem:

- Docs now contain duplicate top-level slice files, old stabilization plans,
  completed slice docs, new profile/search docs, and roadmap status drift.
- Teammates need one current map before continuing.

Likely files:

- `docs/mvp/development-roadmap.md`
- `docs/mvp/requirements.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/architecture.md`
- `docs/mvp/fix/*`
- `docs/mvp/iam/*`
- `docs/mvp/search/*`
- `AGENTS.md`

Tasks:

- Fix roadmap drift where `SEARCH-04` appears both complete and planned.
- Remove, archive, or redirect duplicate top-level slice docs under
  `docs/mvp/`.
- Ensure search slice names match current files:
  `SEARCH-01A`, `SEARCH-01B`, `SEARCH-03`, and `SEARCH-04`.
- Update the active/deferred index if it references stale filenames.
- Confirm chat is the next MVP feature area only after P0 stabilization.
- Keep V2/V3 exclusions visible.

Tests:

```powershell
rg -n "SEARCH-04|SEARCH-02A|SEARCH-02B|SEARCH-03|chat|avatar|OpenSearch|fix-05|planned|complete" docs/mvp AGENTS.md
git diff --check -- docs AGENTS.md
```

Acceptance criteria:

- Roadmap, API contract, requirements, and slice docs agree on completed and
  next work.
- Duplicate or historical docs are clearly marked or moved.
- No product code changes.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p0-04-source-of-truth-documentation-roadmap-cleanup.md`.

### POSTSEARCH-STAB-P0-05 OpenSearch Projection Operational Safety

Status: planned.

One PR: yes.

Problem:

- OpenSearch adds an external dependency and rebuild endpoint.
- Before relying on it, local/demo behavior, authorization, failure mode, and
  MySQL revalidation need an explicit safety baseline.

Likely files:

- `docker-compose*.yml`
- `product-service/src/main/resources/application*.properties`
- `product-service/src/main/java/.../search/*`
- `product-service/src/main/java/.../controller/ListingController.java`
- `product-service/src/test/java/.../search/*`
- `docs/mvp/search/search-04-opensearch-projection.md`

Tasks:

- Confirm default `LISTING_SEARCH_ENGINE=mysql` works without OpenSearch.
- Confirm `LISTING_SEARCH_ENGINE=opensearch` requires reachable OpenSearch or
  returns the documented service-unavailable error.
- Confirm rebuild endpoint requires platform admin authorization.
- Confirm index initialization settings are explicit for local/demo.
- Confirm projection stores only safe public fields.
- Confirm failed projection update does not block authoritative MySQL listing
  state changes unless explicitly documented.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test
rg -n "LISTING_SEARCH_ENGINE|OPENSEARCH|rebuild|search/listings" docker-compose*.yml product-service docs/mvp/search
```

Acceptance criteria:

- MySQL-backed search remains the safe default.
- OpenSearch failure behavior is documented and tested.
- Rebuild is admin-only.
- No schema or endpoint shape changes unless a bugfix PR explicitly requires
  them.

## P1: Should Fix Soon

### POSTSEARCH-STAB-P1-01 Frontend Search/Profile Component Responsibility Cleanup

Status: planned.

Problem:

- Marketplace home, stores browse, account dashboard, profile page, profile
  card, and listing components now own API calls, form state, filters,
  pagination, visual layout, and image display.

Tasks:

- Extract pure helpers or small presentational components only where current
  files are already hard to review.
- Keep route paths, visible copy, query params, and API calls unchanged.
- Keep marketplace, seller portal, and admin UI styles distinct.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

Acceptance criteria:

- Component responsibility is clearer.
- No visible behavior change is intended.

### POSTSEARCH-STAB-P1-02 Backend Search And Listing Service Readability Cleanup

Status: complete.

Problem:

- Product-service now combines listing lifecycle commands, public reads,
  search filters, cursor pagination, media, moderation, and projection update
  behavior.

Tasks:

- Separate search criteria parsing, cursor handling, projection update, and
  MySQL visibility revalidation into small helpers/classes only where it
  reduces review risk.
- Add concise purpose comments for non-obvious projection and visibility
  rules.
- Preserve transaction boundaries and response shapes.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test
```

Acceptance criteria:

- Search/listing code is easier to review before chat or admin expansion.
- Public API responses remain unchanged.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p1-02-backend-search-listing-service-readability-cleanup.md`.

### POSTSEARCH-STAB-P1-03 API Client And DTO Naming Cleanup

Status: complete.

Problem:

- Public listing, public search page, marketplace listing, business store
  listing, avatar upload, and seller label names are easy to confuse after the
  split surfaces.

Tasks:

- Rename internal TypeScript aliases, fixture names, and helper names where
  they imply the wrong surface.
- Do not rename JSON fields or endpoint paths.
- Keep backend DTO renames only if they are internal and low-risk.

Tests:

```powershell
.\mvnw.cmd -pl product-service,auth-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Acceptance criteria:

- Code naming matches marketplace/store/profile boundaries.
- Wire contracts are unchanged.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p1-03-api-client-dto-naming-cleanup.md`.

### POSTSEARCH-STAB-P1-04 Regression Test Organization

Status: complete.

Problem:

- Tests now cover auth, profile/avatar, listing lifecycle, public search,
  OpenSearch, and frontend public surfaces, but the important smoke paths are
  spread across files and docs.

Tasks:

- Group or name tests around product flows rather than implementation history.
- Add missing no-behavior regression tests only for completed rules.
- Document the focused command for each major MVP area.

Tests:

```powershell
.\mvnw.cmd -pl api-gateway,auth-service,product-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Acceptance criteria:

- A teammate can find the right test for auth/profile/avatar/listing/search.
- No production behavior changes.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p1-04-regression-test-organization.md`.

### POSTSEARCH-STAB-P1-05 Shared Object Storage Upload Abstraction

Status: complete.

Problem:

- Listing media and user avatar upload both use S3-compatible object storage
  backed by Google Cloud Storage in demo/VM environments.
- The current implementation can drift in signing rules, content-type checks,
  object verification, URL generation, timeout configuration, and local-demo
  behavior.
- The repeated code is technical storage plumbing, not domain behavior.

Tasks:

- Extract only stable object-storage plumbing into a small shared backend
  library or clearly named adapter package.
- Share signing, upload target creation, object metadata verification, public
  read URI creation, and local-demo storage helpers where the behavior is
  truly identical.
- Keep domain-specific rules in the owning service:
  - listing media allowed types, size limits, object-key prefix, ownership,
    moderation status, and listing image attachment stay in product-service
  - avatar allowed types, size limits, object-key prefix, profile versioning,
    avatar URL update, and public avatar hiding rules stay in auth-service
- Do not create a runtime common service.
- Do not move JPA entities, repositories, controllers, migrations, or business
  workflows into shared code.
- Decide whether the shared code belongs in a new `common-storage` Maven module
  or a smaller internal package after inspecting dependency impact; do not put
  AWS SDK/GCS client code into `common-core`.

Tests:

```powershell
.\mvnw.cmd -pl common-core,auth-service,product-service -am test
rg -n "S3|GCS|signed|uploadUrl|verifyUploaded|createUploadTarget|StorageUploadTarget" auth-service product-service common-* docs/mvp
```

Acceptance criteria:

- Listing media and avatar upload use one shared technical implementation for
  equivalent GCS/S3-compatible upload operations.
- Listing and avatar authorization, validation, persistence, and public URL
  rules remain service-owned.
- Existing listing media and avatar API contracts are unchanged.
- No new storage provider behavior is added.

Completion note:

- See `docs/mvp/fix/stabilization/post-search/postsearch-stab-p1-05-shared-object-storage-upload-abstraction.md`.

## P2: Can Wait

### POSTSEARCH-STAB-P2-01 Docs Folder Structure Cleanup

Status: complete.

Problem:

- `docs/mvp/fix/` and `docs/mvp/iam/` now contain many slice files in one
  folder.

Tasks:

- Propose a folder structure such as:
  - `docs/mvp/fix/stabilization/pre-search/`
  - `docs/mvp/fix/stabilization/post-search/`
  - `docs/mvp/iam/login/`
  - `docs/mvp/iam/signup/`
  - `docs/mvp/iam/user-profile/`
- Move docs only after updating references in roadmap and `AGENTS.md`.
- Do not mix doc moves with code changes.

Tests:

```powershell
rg -n "docs/mvp/fix|docs/mvp/iam|fix-05|USER-05|SEARCH-04" docs AGENTS.md
git diff --check -- docs AGENTS.md
```

Acceptance criteria:

- Docs are easier to navigate.
- Links in roadmap and AGENTS remain valid.

Completion note:

- Slice docs were moved under stabilization and IAM subfolders.
- Added folder indexes at `docs/mvp/fix/README.md` and
  `docs/mvp/iam/README.md`.

### POSTSEARCH-STAB-P2-02 Manual Browser Smoke Checklist Refresh

Status: complete.

Problem:

- Manual smoke docs predate avatar upload, split search, cursor pagination,
  and OpenSearch.

Tasks:

- Refresh the smoke checklist for:
  - native login/register/logout
  - Google login CTA if enabled
  - profile save
  - avatar upload/remove/display
  - individual marketplace search
  - business storefront search
  - cursor load-more behavior
  - listing detail
  - admin listing/business decisions
  - OpenSearch enabled/disabled mode
- Mark any required local/demo services clearly.

Tests:

```powershell
rg -n "smoke|avatar|search|OpenSearch|load more|logout|admin" docs/mvp/fix docs/deploy
```

Acceptance criteria:

- A teammate can smoke-test the current MVP without reading chat history.
- No code changes.

Completion note:

- See
  `docs/mvp/fix/stabilization/post-search/postsearch-stab-p2-02-manual-browser-smoke-checklist-refresh.md`.

### POSTSEARCH-STAB-P2-03 Historical Demo And Deferred Module Cleanup

Status: complete.

Problem:

- Deferred V2 services and tutorial UI folders remain in the repository and
  can confuse new teammates.

Tasks:

- Recheck the active/deferred module index after search/profile work.
- Decide whether to archive V2 service folders or leave them indexed only.
- Confirm inactive frontend V2 screens are not linked from active MVP
  navigation.

Tests:

```powershell
rg -n "inventory|orders|payments|notification|deferred|tutorial|active MVP" docs frontend/src/app
```

Acceptance criteria:

- Active MVP code is visually easier to distinguish from deferred code.
- No V2 behavior is reactivated.

Completion note:

- See
  `docs/mvp/fix/stabilization/post-search/postsearch-stab-p2-03-historical-demo-deferred-module-cleanup.md`.
- The audit found that `/cart` and `/account/addresses` are active V2 routes
  in the current dirty worktree. They remain preserved for the V2 branch and
  are explicitly excluded from the MVP staging boundary.

### POSTSEARCH-STAB-P2-04 Observability And Demo Diagnostics Notes

Status: planned.

Problem:

- Search and avatar storage add external dependencies whose failures need
  obvious diagnostics in demo logs.

Tasks:

- Document key log messages and config flags for avatar storage, listing
  media, OpenSearch, and gateway auth.
- Add counters only if the current service stack already exposes the metrics
  dependency.
- Do not introduce a new observability platform in this cleanup slice.

Tests:

```powershell
rg -n "log\\.|LISTING_SEARCH_ENGINE|USER_AVATAR_STORAGE|OPENSEARCH|S3|GCS" auth-service product-service api-gateway docs
```

Acceptance criteria:

- Demo failures are easier to triage.
- No new infrastructure dependency is introduced.

## Recommended Order

1. `POSTSEARCH-STAB-P0-01`
2. `POSTSEARCH-STAB-P0-02`
3. `POSTSEARCH-STAB-P0-03`
4. `POSTSEARCH-STAB-P0-04`
5. `POSTSEARCH-STAB-P0-05`
6. P1 cleanup slices as capacity allows
7. P2 cleanup slices when onboarding or review friction becomes painful

After P0 is complete, the recommended next MVP feature area is `CHAT-00`
planning and authorization design.
