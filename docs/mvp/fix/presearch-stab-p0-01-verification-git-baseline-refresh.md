# PRESEARCH-STAB-P0-01 Verification And Git Baseline Refresh

Status: complete.

## Goal

Refresh the verification and working-tree baseline before starting search work.

This cleanup does not change product behavior, API contracts, database schema,
routes, UI copy, or runtime configuration.

## Scope

Review completed MVP work across:

- auth/session/sign-up
- individual seller profile
- business onboarding and profile
- listing lifecycle and media
- public marketplace and business store surfaces
- current public browse baseline

## Tasks

- Run the agreed backend and frontend verification commands.
- Record exact command results.
- Review `git status --short --untracked-files=all`.
- Group intentional modified/untracked files by feature area.
- Confirm generated build output is ignored.
- Confirm no active test failure is carried into search work.

## Results

Completed on local Windows development environment on 2026-06-30.

### Backend Verification

The Maven wrapper command did not start from the PowerShell/Codex shell:

```text
.\mvnw.cmd test
```

Result:

```text
Cannot start maven from wrapper
```

The cached Maven binary was then used to run the same reactor test goal:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" test
```

Result:

```text
BUILD SUCCESS
Total time: 01:30 min
Surefire reports: tests=234 failures=0 errors=0 skipped=0
```

Reactor modules:

- `msb-ecom`
- `common-core`
- `common-web`
- `common-testing`
- `product-service`
- `api-gateway`
- `auth-service`

Notes:

- The first sandboxed Maven run could not resolve dependencies because network
  access was restricted. The same command passed with normal local access.
- Docker/Testcontainers was available through Docker Desktop.
- Observed warnings were non-blocking: Flyway/MySQL support warning, Mockito
  dynamic-agent warnings, Java native-access warnings, and SLF4J no-provider
  warnings from test runtime.
- `CommonApiExceptionHandlerTest` intentionally logs a fake
  `password=top-secret` exception while asserting the safe error envelope; it
  is test data, not a real secret.

### Frontend Verification

The first sandboxed frontend test command failed before specs ran because
Angular/esbuild could not read workspace spec files and `node_modules`:

```powershell
cd frontend
npm.cmd test -- --watch=false
```

Sandbox failure summary:

```text
Cannot read directory "../../..": Access is denied.
Could not resolve spec files and Angular testing packages.
```

The same command passed with normal local filesystem access:

```powershell
cd frontend
npm.cmd test -- --watch=false
```

Result:

```text
TOTAL: 185 SUCCESS
```

Frontend build:

```powershell
cd frontend
npm.cmd run build
```

Result:

```text
Application bundle generation complete.
Output location: C:\Users\b\IdeaProjects\msb-ecom\frontend\dist\frontend
```

Known build warnings:

- `marketplace-layout.component.ts` component CSS budget exceeded by `1.31 kB`
  with a total of `5.31 kB`.
- `marketplace-home.component.ts` component CSS budget exceeded by `808 bytes`
  with a total of `4.81 kB`.

These warnings are already captured as future cleanup scope in
`PRESEARCH-STAB-P2-01`.

### Git Status Review

Command:

```powershell
git status --short --untracked-files=all
```

Result:

- Working tree is not clean.
- No accidental generated build output is listed by Git.
- Generated folders observed on disk remain ignored:
  - backend module `target` folders
  - `frontend/.angular`
  - `frontend/dist`
  - `frontend/node_modules`

Intentional modified/untracked groups visible in Git status:

- Auth/login/sign-up:
  - gateway BFF auth controller, native auth service/tests, return URL and
    security config updates
  - auth-service security/admin/public identity label updates
  - Keycloak realm and marketplace theme files
  - IAM, LOGIN, SIGNUP, and USER docs
- Listing/media/public browse:
  - product-service listing DTO, repository, service, auth client, and tests
  - Angular listing, marketplace, public detail, listing service, and test
    fixtures
  - LIST, MEDIA, requirements, API contract, and SEARCH docs
- Public UI split:
  - Angular routes, marketplace layout, marketplace home, business stores
    component/tests, and SITE-01 docs
- Stabilization planning:
  - FIX-03 auth/login stabilization doc
  - FIX-04 pre-search stabilization doc
  - all `presearch-stab-*` slice docs
- Local/demo configuration:
  - `.env`
  - `docker-compose.yml`
  - `docker-compose.demo.yml`

No files were deleted or reverted by this slice.

## Non-Goals

- No production code changes.
- No dependency upgrades.
- No feature flags.
- No schema or migration changes.
- No route, endpoint, or response changes.

## Verification

```powershell
.\mvnw.cmd test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
git status --short --untracked-files=all
```

If Docker/Testcontainers is unavailable locally, record that limitation and
the command that should pass in CI or a Docker-enabled shell.

Recorded result:

- Backend passed through cached Maven binary because the wrapper bootstrap
  failed in this shell.
- Frontend tests and build passed with normal local filesystem access because
  the sandboxed attempt could not read Angular workspace files.
- Docker/Testcontainers was available.

## Acceptance Criteria

- Verification results are documented.
- Git status is reviewed and intentional.
- No accidental generated artifacts are present in Git status.
- No production behavior changes were made.
