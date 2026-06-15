# Product Development Roadmap

## 1. Release Scope

### MVP

- Foundation
- Authentication and user accounts
- Individual seller profile
- Business seller profile and basic store profile
- Listings and media
- Search and public storefront
- Basic buyer/seller chat
- Basic admin moderation for businesses and listings

### V2

- Business cart
- Inventory
- Checkout and payment
- Orders and shipping
- Notifications

### V3

- Reviews and reputation
- Advanced admin and trust operations
- AI assistant
- Advanced analytics

Individual trade completion verification and public completed-sales count are
retained as approved product design, but implementation is deferred until the
basic MVP chat and listing experience has been validated.

## 2. Current Work Boundary

Only Phase 0 and Phase 1 setup tasks are approved for implementation.

Do not implement registration, login, seller profiles, listings, search, chat,
moderation, cart, payment, or any other application feature during setup.

At the end of Phase 1, stop and review the repository before creating feature
implementation tasks.

## 3. Phase 0: Documentation Baseline

Phase 0 is intentionally small.

### P0-01 Confirm release boundaries

- Treat this roadmap as the release authority.
- Label requirements, APIs, and database sections as MVP, V2, or V3.
- Do not delete deferred contracts; they remain planning references.

### P0-02 Record current repository state

- Record modules, build tools, Java/Node versions, and existing infrastructure.
- Record current uncommitted files without modifying or reverting them.
- Record which existing tests currently pass or fail.

### P0-03 Create implementation issue template

The template must require:

- Requirement ID
- Release (`MVP`, `V2`, or `V3`)
- Scope and non-goals
- API/database impact
- Authorization impact
- Tests and acceptance criteria

Phase 0 completion:

- Documentation has one release classification.
- Existing code behavior has not been changed.

## 4. Phase 1: Engineering Setup

Phase 1 creates only the development foundation required for later features.

### P1-01 Verify local toolchain

Tasks:

- Confirm Java 21 and Maven wrapper execution.
- Confirm supported Node.js and npm versions.
- Confirm Docker Compose availability.
- Document exact setup and verification commands.

Deliverable:

- Updated development setup instructions.

Tests:

- Maven version command succeeds.
- Frontend dependency/tool version commands succeed.
- Docker Compose configuration parses.

### P1-02 Establish backend build baseline

Tasks:

- Build all existing Maven modules without changing product behavior.
- Identify failing modules or tests.
- Add a documented root verification command.
- Keep existing service boundaries unchanged.

Deliverable:

- Reproducible backend build/test baseline.

Tests:

- Root Maven compile succeeds, or known failures are documented with evidence.
- Existing passing tests remain passing.

### P1-03 Establish frontend build baseline

Tasks:

- Install dependencies using the existing lockfile.
- Run the current Angular test/build commands.
- Document current warnings and failures.
- Do not create marketplace, seller, or admin feature pages.

Deliverable:

- Reproducible frontend build/test baseline.

Tests:

- Angular build succeeds, or known failures are documented.
- Existing frontend tests remain passing.

### P1-04 Decide one authentication architecture

Tasks:

- Compare the repository's custom JWT/session and Keycloak paths.
- Select one authentication approach for later feature work.
- Document access token, refresh, logout, and service validation behavior.
- Document admin authentication hardening expectations.
- Do not implement registration or login.

Deliverable:

- Authentication architecture decision record.

Tests:

- Documentation review only; no application behavior change.

### P1-05 Define MySQL ownership and migration conventions

Tasks:

- Map each current and planned domain to its owning service/schema.
- Confirm ID representation, UTC timestamps, `utf8mb4`, InnoDB, and money
  representation.
- Define Flyway naming and backward-compatible migration rules.
- Do not create feature tables.

Deliverable:

- Database convention and ownership record.

Tests:

- Existing migrations validate.
- No Hibernate schema auto-update is introduced.

### P1-06 Create minimal backend shared modules

Create only:

```text
common-core
common-web
common-testing
```

Initial allowed content:

- `common-core`: identifiers, money/currency value type, clock abstraction
- `common-web`: error envelope and correlation-ID primitives
- `common-testing`: shared test assertions and Testcontainers helpers

Do not create `common-security` or `common-events` until authentication and
event work actually need them.

Do not add:

- JPA entities
- Repositories
- Business services
- Feature DTOs
- Feature constants
- Database migrations

Deliverable:

- Small Maven modules referenced only where immediately useful.

Tests:

- Each shared module builds independently.
- Architecture test prevents JPA entities and Spring business services inside
  shared modules.

### P1-07 Standardize API error and correlation contracts

Tasks:

- Define the error envelope in `common-web`.
- Generate or accept a safe correlation ID at the gateway.
- Propagate the ID to service logs and responses.
- Do not change feature endpoint behavior beyond error/correlation plumbing.

Deliverable:

- Shared technical API contract.
- Usage reference: `docs/p1-07-common-web-reference.md`.

Tests:

- Missing correlation ID is generated.
- Valid client correlation ID is propagated.
- Invalid/oversized correlation ID is replaced.
- Error responses contain no stack trace or secret.

### P1-08 Define runtime configuration rules

Tasks:

- Separate local defaults from secrets.
- Document environment variable naming.
- Remove no secrets during this task unless replacement configuration is ready.
- Define AWS Secrets Manager usage for production.
- Do not create a runtime common/config service.

Deliverable:

- Configuration and secret-management guide.
- Guide location: `docs/p1-08-runtime-configuration.md`.

Tests:

- Repository secret scan runs.
- Required local variables are represented in `.env.example`.

### P1-09 Prepare Angular workspace structure

Tasks:

- Document the target three-app workspace:
  `marketplace`, `seller-portal`, and `admin-portal`.
- Identify reusable `auth`, `api-client`, `models`, `ui`, `validation`, and
  `observability` libraries.
- Preserve the existing frontend entry point during setup.
- Do not create feature pages or migrate existing features yet.

Deliverable:

- Frontend workspace migration plan or empty buildable shells only if required
  to validate the workspace structure.
- Migration plan location: `docs/p1-09-frontend-workspace-plan.md`.

Tests:

- Existing frontend still builds.
- Any created shell builds without application features.

### P1-10 Add CI baseline

Tasks:

- Add backend compile/test job.
- Add frontend build/test job.
- Add migration validation.
- Add dependency and secret scanning.
- Do not add deployment automation.

Deliverable:

- Pull-request quality checks.
- CI baseline guide: `docs/p1-10-ci-baseline.md`.

Tests:

- CI configuration validates.
- A controlled test failure causes the relevant job to fail.

### P1-11 Add code quality and architecture checks

Tasks:

- Apply existing Java and Angular formatting/lint conventions.
- Add checks preventing service-to-service database access.
- Add checks preventing business code in common modules.
- Define package ownership conventions.

Deliverable:

- Documented, automated architecture guardrails.
- Guardrails guide: `docs/p1-11-architecture-guardrails.md`.

Tests:

- Example violations are detected by the checks.

### P1-12 Produce Phase 1 verification report

Report:

- Commands run and outcomes
- Existing failures still unresolved
- Authentication decision
- Database ownership decision
- Shared modules created
- CI checks added
- Files intentionally left unchanged
- Risks blocking the first feature slice

Report location:

- `docs/phase1-verification-report.md`

Phase 1 completion:

- Backend and frontend baselines are reproducible.
- Authentication and database conventions are decided.
- Minimal shared technical modules build.
- Error and correlation contracts exist.
- CI baseline runs.
- No application feature has been implemented.

## 5. Future MVP Planning

After Phase 1 approval, create small implementation tasks for these areas only:

1. Authentication and accounts
2. Individual and business seller profiles
3. Listings and media
4. Search and storefront
5. Basic buyer/seller chat
6. Basic business/listing admin moderation

Do not create V2 or V3 implementation tasks until MVP usage validates the need.

## 6. Deferred Release Summaries

### V2

Business commerce:

- Cart
- Inventory
- Checkout/payment
- Orders/shipping
- Notifications

### V3

Trust, intelligence, and growth:

- Reviews/reputation
- Advanced admin/trust operations
- AI assistant
- Advanced analytics
