# FIX-04 Pre-Search Stabilization Sprint

Status: complete.

## Scope

This stabilization sprint reviews only completed MVP work before starting the
next search slices.

Completed scope reviewed:

- IAM-00 through IAM-06
- LOGIN-01 and LOGIN-02
- SIGNUP-00 through SIGNUP-05
- IND-01 and IND-02
- BUS-01 through BUS-04
- LIST-00 through LIST-07
- MEDIA-01 object storage image delivery
- LIST frontend stabilization
- SITE-00 logical three-site separation
- SITE-01 public UI surface split
- SEARCH-00 and SEARCH-01 planning/current browse baseline

This sprint must not add product features. The goal is to make the completed
work easier to trust, review, and build on before `SEARCH-02A`, `SEARCH-02B`,
and `SEARCH-03`.

## Stabilization Rules

Every cleanup slice must:

- be one pull request
- preserve current user-visible behavior
- preserve API contracts
- preserve database schema unless a verified migration or data-safety bug
  requires a forward-safe migration
- avoid new routes, endpoints, tables, product states, workflows, filters,
  search behavior, chat, checkout, cart, inventory, payment, order, shipping,
  notification, review, reputation, or AI behavior
- include verification notes in the PR
- update documentation only when it clarifies the completed behavior

## Review Summary

The completed MVP work has the right shape for moving into search: public
listing browse exists, individual marketplace and business store surfaces are
separated, listing detail works, listing media uses object storage, sellers can
manage listing lifecycle states, and auth/session work follows the gateway BFF
boundary.

The main risk before search is not missing product behavior. The risk is drift:

- docs and route names have changed several times while the UI split evolved
- listing UI behavior is now richer and needs a crisp regression baseline
- media storage configuration should be checked for secret and environment
  hygiene before more teammates copy it
- public browse and storefront code still uses an interim shared feed, so the
  boundary must be clearly documented before backend search endpoints are added
- frontend components grew during LIST work and may need no-behavior cleanup
  so SEARCH work does not pile onto hard-to-review files

## P0: Must Fix

### PRESEARCH-STAB-P0-01 Verification And Git Baseline Refresh

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p0-01-verification-git-baseline-refresh.md`

Problem:

- Completed LIST, MEDIA, SITE, auth, and public browse work spans backend,
  frontend, docs, Keycloak config, and environment files.
- Search work should start from a known green baseline and an intentionally
  reviewed working tree.

Likely files:

- `docs/mvp/fix/fix-04-pre-search-stabilization-sprint.md`
- optional verification record under `docs/mvp/fix/`

Tasks:

- Run and record the focused backend and frontend verification commands.
- Review `git status --short --untracked-files=all`.
- Classify intentional modified/untracked files by feature area.
- Confirm no generated build output is accidentally tracked.
- Confirm no active test failure is being carried into search.

Tests:

```powershell
.\mvnw.cmd test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

Acceptance criteria:

- Verification results are documented.
- Any local Docker/Testcontainers limitation is explicitly named.
- Git status is reviewed and contains no accidental generated artifacts.
- No production behavior changes.

### PRESEARCH-STAB-P0-02 Public Surface Contract Regression Audit

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p0-02-public-surface-contract-regression-audit.md`

Problem:

- `/`, `/marketplace`, `/stores`, `/listings/{listingId}`, and marketplace
  account listing routes now have distinct jobs.
- Search work should not accidentally collapse individual marketplace and
  business store behavior back into one generic page.

Likely files:

- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.spec.ts`
- `frontend/src/app/features/stores/business-stores.component.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `docs/mvp/site/site-01-public-ui-surface-split.md`
- `docs/mvp/search/search-00-search-storefront-read-model-plan.md`

Tasks:

- Tighten tests that document route ownership and public navigation.
- Verify marketplace browse displays only individual listings.
- Verify store browse displays only business listings.
- Verify public listing detail remains guest-readable only for approved active
  listings.
- Update docs if current route ownership is unclear.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/app.routes.spec.ts --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/features/marketplace/public-listing-detail.component.spec.ts
```

Acceptance criteria:

- Route and surface boundaries are covered by focused tests.
- No visible copy, route, API, or data behavior changes.

### PRESEARCH-STAB-P0-03 Media Storage Secret And Environment Hygiene

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p0-03-media-storage-secret-environment-hygiene.md`

Problem:

- MEDIA-01 introduced object storage credentials and bucket configuration.
- Before teammates build on media/search, config should be checked so real
  secrets do not become part of shared demo defaults or documentation.

Likely files:

- `.env.example`
- `.env.demo`
- `docker-compose.yml`
- `docker-compose.demo.yml`
- `product-service/src/main/resources/application*.properties`
- `docs/mvp/list/media-01-object-storage-image-delivery.md`
- `docs/deploy/team-demo-readme.md`

Tasks:

- Search for object-storage access keys, secret keys, bucket names, and
  endpoint URLs.
- Ensure real credentials are not documented as shared defaults.
- Keep local-only secret handling local.
- Keep demo placeholders clearly marked when needed.
- Confirm public image delivery still uses existing signed/public URL behavior.

Tests:

```powershell
rg -n "GOOG|HMAC|ACCESS_KEY|SECRET|storage.googleapis.com|bucket|S3|GCS" .env* docker-compose*.yml product-service docs
git diff --check
```

Acceptance criteria:

- No real shared secret is introduced by the cleanup PR.
- Existing media API contracts and runtime behavior are preserved.
- No database schema changes.

### PRESEARCH-STAB-P0-04 Source-Of-Truth Documentation Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p0-04-source-of-truth-documentation-cleanup.md`

Problem:

- Some completed work has older docs, newer docs, and roadmap references that
  can be read in different ways.
- Search should start from one obvious source of truth.

Likely files:

- `docs/mvp/development-roadmap.md`
- `docs/mvp/requirements.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/list/*`
- `docs/mvp/search/*`
- `docs/mvp/site/*`
- `docs/mvp/ui/*`
- `AGENTS.md`

Tasks:

- Check for duplicate slice names and stale top-level slice docs.
- Align route names and surface names across roadmap, API contract, and slice
  docs.
- Confirm `SEARCH-02A`, `SEARCH-02B`, and `SEARCH-03` are still the next
  feature slices after stabilization.
- Keep older docs only when they are clearly marked historical or redirected.

Tests:

```powershell
rg -n "LIST-04|LIST-07|MEDIA-01|SITE-01|SEARCH-02A|SEARCH-02B|SEARCH-03|business store|marketplace" docs/mvp AGENTS.md
git diff --check -- docs AGENTS.md
```

Acceptance criteria:

- Roadmap and slice docs do not conflict about current behavior.
- No product behavior, API contract, or schema changes.

## P1: Should Fix

### PRESEARCH-STAB-P1-01 Listing Frontend Component Responsibility Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p1-01-listing-frontend-component-responsibility-cleanup.md`

Problem:

- Listing form, listing management, gallery, and public marketplace components
  grew during LIST stabilization.
- Search UI should not add more logic to already crowded components.

Likely files:

- `frontend/src/app/features/listings/listing-draft-form.component.ts`
- `frontend/src/app/features/listings/listing-management.component.ts`
- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.ts`
- `frontend/src/app/shared/components/ui/listing-image-gallery.component.ts`

Tasks:

- Extract pure helpers or presentational pieces only where duplication is
  already visible.
- Keep inputs, outputs, visible copy, routes, and API calls unchanged.
- Avoid creating a broad design system.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

Acceptance criteria:

- Components are easier to review.
- No visual or behavioral change is intended.
- Existing tests pass.

### PRESEARCH-STAB-P1-02 Listing Lifecycle Regression Test Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p1-02-listing-lifecycle-regression-test-cleanup.md`

Problem:

- Listing lifecycle behavior now allows editing and resubmission from draft,
  pending review, active, and closed states with specific submit-button rules.
- The rules should be easy to find before search adds more listing entry
  points.

Likely files:

- `frontend/src/app/features/listings/listing-draft-form.helpers.ts`
- `frontend/src/app/features/listings/listing-draft-form.component.spec.ts`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
- `docs/mvp/list/list-frontend-stabilization.md`

Tasks:

- Organize existing lifecycle tests around status-specific rules.
- Add missing no-behavior regression tests if a completed rule is undocumented
  by tests.
- Keep API behavior and listing statuses unchanged.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test "-Dtest=ListingDraftApiTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --include=src/app/features/listings/listing-draft-form.component.spec.ts
```

Acceptance criteria:

- Completed lifecycle rules have clear regression coverage.
- No production behavior changes unless a test exposes an existing bug, which
  should be fixed in a separate bugfix PR.

### PRESEARCH-STAB-P1-03 Public Listing DTO And Client Naming Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p1-03-public-listing-dto-client-naming-cleanup.md`

Problem:

- Public listing card/detail/store interim code shares response models while
  product direction now splits individual marketplace and business store
  experiences.
- Names should reduce confusion without changing wire contracts.

Likely files:

- `product-service/src/main/java/com/msb/ecom/product_service/dto/PublicListingResponse.java`
- `frontend/src/app/core/models/listing.model.ts`
- `frontend/src/app/core/services/listing.service.ts`
- related tests

Tasks:

- Rename internal TypeScript aliases, helper names, or test fixture names if
  they imply future behavior incorrectly.
- Do not rename JSON fields or endpoint paths.
- Do not split backend APIs in this cleanup.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
```

Acceptance criteria:

- Code naming matches current public listing/store boundary.
- API wire shape is unchanged.

### PRESEARCH-STAB-P1-04 Backend Listing Service Readability Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p1-04-backend-listing-service-readability-cleanup.md`

Problem:

- Product-service listing code now covers drafts, media, moderation,
  close/edit/resubmit behavior, public browse, and public detail.
- Before search adds query behavior, completed command/read logic should be
  easier to review.

Likely files:

- `product-service/src/main/java/com/msb/ecom/product_service/service/ListingService.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/ListingDraftRepository.java`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`

Tasks:

- Extract small private methods only where they clarify existing rules.
- Add concise purpose comments for non-obvious authorization/state helpers.
- Preserve transaction boundaries, queries, DTO fields, and responses.

Tests:

```powershell
.\mvnw.cmd -pl product-service -am test
```

Acceptance criteria:

- Listing service behavior and API output are unchanged.
- The next search PR can add read-query behavior without mixing unrelated
  refactor noise.

## P2: Nice To Have

### PRESEARCH-STAB-P2-01 Marketplace CSS Budget Cleanup

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p2-01-marketplace-css-budget-cleanup.md`

Problem:

- Frontend build passes, but marketplace component CSS has budget warnings.
- Search UI will add more styles, so reducing style bulk now may prevent noisy
  future builds.

Likely files:

- `frontend/src/app/features/marketplace/marketplace-home.component.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- optional component stylesheet files if the current Angular setup supports
  them cleanly

Tasks:

- Remove duplicated CSS rules.
- Move repeated tokens to existing global/theme-level styles only when already
  justified.
- Do not change the theme or layout behavior.

Tests:

```powershell
cd frontend
npm.cmd run build
```

Acceptance criteria:

- CSS budget warnings are reduced or documented.
- No visible UI redesign is included.

### PRESEARCH-STAB-P2-02 Manual Browser Smoke Checklist

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p2-02-manual-browser-smoke-checklist.md`

Problem:

- Browser testing has happened during implementation, but the current smoke
  path is spread across chat context and slice docs.

Likely files:

- `docs/mvp/fix/`
- `docs/deploy/team-demo-readme.md`

Tasks:

- Write a short manual checklist for login/logout, individual seller
  activation, draft listing with images, submit for review, admin approval,
  public marketplace browse, business stores browse, and listing detail.
- Mark any required local services clearly.
- Do not add automation in this slice.

Tests:

```powershell
rg -n "smoke|login|listing|stores|admin approval" docs/mvp/fix docs/deploy
```

Acceptance criteria:

- A teammate can smoke-test completed MVP flows without reading chat history.
- No code changes are required.

### PRESEARCH-STAB-P2-03 Small Shared UI Primitive Consolidation

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p2-03-small-shared-ui-primitive-consolidation.md`

Problem:

- Some buttons, empty states, cards, and status pills are repeated after the
  marketplace/seller/admin split.

Likely files:

- `frontend/src/app/shared/components/ui/*`
- marketplace/listing/account components that already duplicate the same
  markup

Tasks:

- Reuse existing shared primitives only where duplication is obvious.
- Add a new primitive only if it replaces repeated structure immediately.
- Keep marketplace, seller portal, and admin visual styles distinct.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false
```

Acceptance criteria:

- Duplication decreases.
- No broad design-system work is introduced.
- No behavior changes.

### PRESEARCH-STAB-P2-04 Historical Demo And Deferred-Service Index

Status: complete.

One PR: yes.

Reference:

- `docs/mvp/fix/presearch-stab-p2-04-historical-demo-deferred-service-index.md`

Problem:

- V2/tutorial service code and older docs still exist in the repository.
- The current roadmap says they are deferred, but a compact index would help
  teammates avoid reviving them accidentally.

Likely files:

- `docs/mvp/architecture.md`
- `docs/mvp/development-roadmap.md`
- `docs/deploy/team-demo-readme.md`
- optional new doc under `docs/mvp/fix/`

Tasks:

- List which services/screens/docs are active MVP versus deferred V2/tutorial.
- Link to the current source-of-truth docs.
- Do not delete code in this nice-to-have slice.

Tests:

```powershell
rg -n "deferred|V2|tutorial|active MVP" docs/mvp docs/deploy
git diff --check -- docs
```

Acceptance criteria:

- Teammates can identify active versus deferred code quickly.
- No build, API, schema, or behavior changes.

## Recommended Order

1. `PRESEARCH-STAB-P0-01`
2. `PRESEARCH-STAB-P0-02`
3. `PRESEARCH-STAB-P0-03`
4. `PRESEARCH-STAB-P0-04`
5. P1 cleanup slices as capacity allows
6. P2 cleanup slices only when they reduce review or onboarding friction

After P0 is complete, `SEARCH-02A` can start without carrying obvious
stabilization debt into the search implementation.
