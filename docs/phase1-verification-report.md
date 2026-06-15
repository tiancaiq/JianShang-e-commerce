# Phase 1 Verification Report

Date: 2026-06-15

## Scope

This report closes Phase 1 setup work for the approved MVP roadmap. Phase 1
prepared decisions, shared technical modules, runtime configuration rules,
frontend workspace planning, CI checks, and architecture guardrails.

No MVP application feature was intentionally implemented in Phase 1.

## Commands Run

| Command | Outcome |
| --- | --- |
| `mvn.cmd test` | Passed full backend reactor: 11 modules, 46 tests, 0 failures |
| `npm.cmd run build` in `frontend/` | Passed |
| `npm.cmd test -- --watch=false` in `frontend/` | Passed: 2 tests |
| `python tools\architecture_checks.py` | Passed |
| `python tools\architecture_checks.py --self-test` | Passed; controlled violations detected |
| Python YAML parse for `.github/workflows/pull-request-quality.yml` | Passed |
| `git diff --check` | Passed |
| Env coverage check against `.env.example` | Passed |
| High-confidence secret-pattern scan | Passed |
| `npm.cmd audit --audit-level=critical` | Passed; high-severity transitive advisories remain |

Notes:

- `npm` through PowerShell is blocked by local execution policy, so Windows
  verification uses `npm.cmd`.
- Maven verification used the cached Maven wrapper distribution path because
  the wrapper setup was created during Phase 1 and the local PowerShell wrapper
  path has had compatibility friction.
- Maven produced Java 25, Mockito dynamic-agent, SLF4J, Kafka
  `junit-platform.properties`, and Flyway/MySQL support warnings. These did
  not fail tests.

## Authentication Decision

Accepted decision:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`

Summary:

- Keycloak is the sole identity provider target.
- API gateway acts as BFF for first-party Angular applications.
- Browser applications should not store OAuth access or refresh tokens in
  browser storage.
- Services must validate Keycloak JWTs independently and still enforce
  resource ownership and business membership.

Current state:

- Existing custom JWT/session code remains legacy.
- Registration/login was not implemented or migrated in Phase 1.
- Future auth slices must replace custom JWT, browser `localStorage` token
  use, and shared HS256 secret behavior.

## Database Ownership Decision

Accepted decision:

- `docs/adr/0002-mysql-database-ownership-and-migration-conventions.md`

Summary:

- MySQL 8 is the default transactional database for new MVP application data.
- Each service owns its schema and Flyway history.
- Services must not query or update another service database.
- New IDs use ULID strings.
- Timestamps use UTC `DATETIME(6)` mapped to Java `Instant`.
- Money uses `DECIMAL(19,4)` plus ISO currency code.

Current state:

- Existing PostgreSQL auth path and MongoDB product path remain legacy.
- No feature tables or data migrations were created in Phase 1.
- No existing migration was rewritten.

## Shared Modules Created

| Module | Purpose |
| --- | --- |
| `common-core` | Transport-neutral technical primitives such as money, time, and ULID helpers |
| `common-web` | HTTP error envelope, correlation ID validation/filtering, exception plumbing |
| `common-testing` | Reusable testing helpers, including MySQL Testcontainers factory/assertions |

Shared module guardrails:

- No JPA entities in common modules.
- No repositories in common modules.
- No business services/controllers in common modules.
- No domain-specific package roots in common modules.

## Error And Correlation Contracts

Implemented in `common-web`:

- Standard `ApiErrorEnvelope`
- `ApiError`
- `FieldError`
- Safe `X-Correlation-Id` handling
- Correlation ID response header propagation
- SLF4J MDC population
- Common exception-to-envelope mapping

Reference:

- `docs/p1-07-common-web-reference.md`

Known boundary:

- Spring Security-generated `401` and `403` responses are not fully
  standardized yet. That belongs to the Keycloak/security migration.

## Runtime Configuration

Added:

- `docs/p1-08-runtime-configuration.md`
- Expanded `.env.example`
- `.gitignore` rules for `.env` and `.env.*`

Verified:

- All currently referenced environment variables are represented in
  `.env.example`.

Known issue:

- `.env` and `.env.dev` are currently tracked by Git. They should be rotated
  and removed in a separate cleanup slice.

## Frontend Workspace Planning

Added:

- `docs/p1-09-frontend-workspace-plan.md`

Target Angular workspace:

```text
frontend/
  apps/
    marketplace/
    seller-portal/
    admin-portal/
  libs/
    auth/
    api-client/
    models/
    ui/
    validation/
    observability/
```

Current state:

- Existing single Angular app remains the active entry point.
- No new app shells or feature pages were created.
- Existing frontend build and tests pass.

## CI Checks Added

Workflow:

- `.github/workflows/pull-request-quality.yml`

Jobs:

- Backend compile/test
- Frontend build/test
- Migration-owning service validation
- Dependency scan
- High-confidence secret scan
- Architecture guardrails

Reference:

- `docs/p1-10-ci-baseline.md`

Controlled failure:

- Workflow supports `workflow_dispatch` with `force_failure_job` to verify a
  selected job fails intentionally in GitHub Actions.

Local limitation:

- Controlled failure can only be executed inside GitHub Actions.

## Architecture Guardrails

Added:

- `tools/architecture_checks.py`
- `docs/p1-11-architecture-guardrails.md`

Checks:

- Service Java source stays under its package root.
- Services do not import other service packages.
- Resource files do not point datasource URLs at another service database.
- SQL migrations do not qualify references with another service schema.
- Common modules stay free of persistence, repositories, business services,
  controllers, and domain package names.

Verification:

- Normal guardrail check passed.
- Self-test created temporary violations and confirmed they are detected.

## Files Intentionally Left Unchanged

Phase 1 intentionally did not complete these migrations:

- Legacy custom JWT/login implementation in `auth-service`
- Legacy browser token behavior in Angular
- Legacy PostgreSQL auth database configuration
- Legacy MongoDB product-service path
- Existing tutorial-era docs under `Project-Context` and older docs
- Existing feature-like tutorial screens in the Angular app
- Existing V2 services and APIs for cart/order/payment/inventory behavior
- Existing `.env` and `.env.dev` tracked files

These areas require separate, explicit slices.

## Existing Failures Or Risks Still Unresolved

1. Legacy auth is not the accepted target architecture.
   Custom JWT, localStorage token use, and shared symmetric secret behavior
   must be replaced before implementing new account/auth features.

2. Legacy storage is mixed.
   New MVP data should use MySQL per ADR-0002, while current auth/product paths
   still use PostgreSQL and MongoDB.

3. Tracked env files need cleanup.
   `.env` and `.env.dev` are tracked and should be rotated/removed in a
   dedicated cleanup slice.

4. Frontend dependency advisories remain.
   `npm audit --audit-level=critical` passes, but high-severity transitive
   Angular/build-tooling advisories remain and need a dependency remediation
   slice.

5. Security error envelope is incomplete.
   MVC errors use the standard envelope, but Spring Security `401/403`
   responses still need standardization during the Keycloak/security work.

6. CI has not been executed on GitHub yet.
   The workflow YAML parses locally, but the controlled failure and hosted
   runner behavior must be confirmed after pushing.

7. Some Java warning cleanup remains.
   Mockito dynamic-agent warnings, Java 25 native access warnings, SLF4J test
   provider warnings, and duplicate Kafka `junit-platform.properties` warnings
   do not fail tests but should be cleaned up later.

## Risks Blocking The First Feature Slice

Before starting `Auth/accounts` implementation:

- Reconcile accepted Keycloak BFF architecture with the existing `auth-service`
  custom JWT code.
- Decide the first migration slice for identity tables and Keycloak subject
  mapping.
- Remove or quarantine legacy frontend auth assumptions so new auth code does
  not extend `localStorage` token behavior.

Before starting seller/listing data features:

- Create the target MySQL schema ownership plan for marketplace data.
- Avoid adding new MongoDB-backed MVP data.
- Decide whether marketplace data begins inside evolved `product-service` as
  planned or needs a separate deployment decision.

Before relying on CI as a merge gate:

- Push the workflow and run it in GitHub Actions.
- Confirm the controlled failure input behaves as expected.

## Phase 1 Completion Assessment

Phase 1 completion criteria:

| Criterion | Status |
| --- | --- |
| Backend baseline reproducible | Passed locally |
| Frontend baseline reproducible | Passed locally |
| Authentication convention decided | Done: ADR-0001 |
| Database convention decided | Done: ADR-0002 |
| Minimal shared technical modules build | Done |
| Error and correlation contracts exist | Done |
| CI baseline exists | Done |
| Architecture guardrails exist | Done |
| No new MVP app feature implemented | Preserved |

Phase 1 is ready for review. The first MVP feature slice should begin only
after choosing the exact auth/account migration slice and acknowledging the
legacy-code boundaries above.
