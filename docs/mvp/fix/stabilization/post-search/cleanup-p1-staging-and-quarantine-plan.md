# CLEAN-P1 Staging And Quarantine Plan

Status: Complete  
Date: 2026-07-19

## Goal

Separate the current mixed worktree into reviewable buckets before teammates
start coding on top of it.

This cleanup is documentation-only. It does not delete code, stage files,
change application behavior, or decide that V2/V3 work is invalid. It only
defines what should be included in MVP now and what should be parked for later.

## Current Worktree Shape

The repository currently contains several streams of work at the same time:

- MVP stabilization and verification fixes.
- MVP marketplace/auth/listing/search/chat/admin changes.
- Business seller/store changes that are MVP-adjacent but should be staged
  carefully because the user-facing priority is currently marketplace-first.
- V2 commerce work: cart, inventory, order, address, and checkout preparation.
- V3/AI work: agent service, listing knowledge publication, embeddings/RAG
  preparation, and AI docs.
- Local environment and deployment/demo configuration changes.
- Generated or local test artifacts.

Because several tracked files contain more than one stream of work, the next
cleanup should use hunk staging instead of whole-file staging.

## CLEAN-P1-01 MVP Staging Plan

### Recommendation

Create a first teammate-ready MVP PR that contains only:

- Verification baseline fixes from POSTSEARCH-STAB-P0.
- Marketplace-first MVP flows that are already completed and verified.
- Documentation that describes the active MVP state.

Do not include V2 cart/inventory/order/address work or V3 AI work in this PR.

### Good MVP Candidates

These groups are likely acceptable for an MVP stabilization PR after review:

- `docs/mvp/fix/stabilization/post-search/cleanup-p0-worktree-boundary-and-verification.md`
- P0 verification support:
  - root `pom.xml` Surefire test JVM configuration for Byte Buddy on newer JDKs.
  - frontend marketplace layout spec updates needed for current component
    dependencies.
  - `.gitignore` entries for local browser/test artifacts.
- Marketplace browse/search/listing/storefront MVP work:
  - public marketplace route behavior.
  - listing card/detail/search DTO naming cleanup.
  - public approved listing visibility behavior.
  - storefront/search contract docs.
- Auth/session/account MVP work:
  - Keycloak-backed session behavior.
  - profile/session awareness.
  - logout redirect and login stability fixes.
- Basic chat MVP work:
  - buyer/seller conversation access checks.
  - current floating chat and conversation shell behavior.
  - tests for chat participant authorization.
- Basic moderation/admin MVP work:
  - listing moderation access checks.
  - business/listing approval documents already tied to MVP.

### Mixed Files That Need Hunk Staging

These files should not be staged wholesale without another review:

- `pom.xml`
  - Include: Surefire/Byte Buddy test configuration if needed.
  - Exclude from MVP PR: `inventory-service` and `order-service` module
    additions unless the PR intentionally becomes a V2 commerce PR.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`
  - Include only MVP route fixes.
  - Exclude from MVP PR: cart, inventory, and address-book routes.
- `frontend/src/app/app.routes.ts`
  - Include only MVP marketplace/account/admin/storefront routes.
  - Exclude from MVP PR: cart route and seller inventory route.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
  - Include auth/logout/session fixes if they are required for MVP.
  - Exclude V2 cart count loading/reset behavior.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
  - Include current chat test double fixes and MVP auth expectations.
  - Exclude cart-specific expectations unless a V2 PR is being prepared.
- `docs/mvp/development-roadmap.md`
  - Include MVP status and stabilization records.
  - Exclude V2/V3 completion claims from an MVP-only PR unless clearly marked
    as parked or completed outside the MVP release.
- `docs/mvp/api-contract.md`, `docs/mvp/database.md`,
  `docs/mvp/architecture.md`, and `docs/mvp/requirements.md`
  - Include MVP contract corrections.
  - Exclude V2/V3 contract expansions from the MVP PR.
- `docker-compose*.yml` and `run.sh`
  - Include only local infrastructure required to run the current MVP.
  - Exclude AI, cart/order/inventory, and optional demo services until their
    own PRs.

### Acceptance Criteria

- A reviewer can understand the MVP state without reading V2 or AI code.
- `npm test`, `npm run build`, and the Maven MVP reactor pass.
- No `.env` file with local secrets is staged.
- No V2/V3 service is required to run the marketplace MVP.

## CLEAN-P1-02 V2 Quarantine Plan

### Recommendation

Keep V2 commerce work in the repository for now, but quarantine it from the
MVP PR. The safest shape is a later dedicated V2 branch/PR.

### V2 Work To Park

Park these groups together:

- `inventory-service/`
- `order-service/`
- frontend cart pages/components/services/models.
- frontend business inventory pages/components/services/models.
- address-book APIs, models, repositories, services, and migrations.
- gateway cart, inventory, and address routes.
- V2 commerce docs under `docs/v2/commerce/`.
- V2 commerce contract updates in MVP docs unless they are clearly labeled as
  deferred context.

### Why

Cart, inventory, orders, payment, shipping, and notifications are V2 by the
approved roadmap. Mixing them into the MVP baseline makes code review harder
and increases the chance that teammates build against unfinished commerce
contracts.

### Acceptance Criteria

- MVP can build and run without `inventory-service` and `order-service`.
- Cart and inventory UI are not reachable from active MVP navigation.
- V2 docs remain available but do not redefine the MVP release target.

## CLEAN-P1-03 AI Quarantine Plan

### Recommendation

Move AI-related work into a separate V3/AI branch or leave it unstaged until
the MVP marketplace is stable.

### AI Work To Park

Park these groups together:

- `agent-service/`
- `docs/mvp/ai/`
- product-service listing knowledge package, controllers, DTOs, tests, and
  migrations.
- OpenAI/RAG/embedding configuration.
- any Docker Compose or runtime entries required only by the AI service.

### Why

AI is V3 in the approved roadmap. It is valuable, but it should not become a
runtime dependency for public browsing, listing management, search, profile, or
chat during MVP stabilization.

### Acceptance Criteria

- Marketplace browsing, listing, search, profile, and chat work when AI is not
  running.
- AI migrations and AI publication paths are reviewed separately from MVP
  listing/search behavior.
- No OpenAI key is required for MVP tests or local MVP startup.

## CLEAN-P1-04 Environment Cleanup Review

### Recommendation

Review environment files before any staging. Do not commit local secrets.

### Files To Inspect

Run these commands before staging environment changes:

```powershell
git status --short -- .env .env.* *.env
git diff -- .env .env.dev .env.example .env.demo.example
```

### Cleanup Rule

- Keep real local values in untracked `.env.local` or machine-local files.
- Keep safe placeholders in `.env.example` and `.env.demo.example`.
- Do not commit OpenAI keys, cloud provider keys, Keycloak admin passwords,
  database passwords, SMTP credentials, or storage credentials.
- Do not edit `.env` during cleanup unless the task is explicitly about
  environment configuration.

### Acceptance Criteria

- No real secret appears in `git diff --cached`.
- Teammates have example env files with enough placeholders to run local MVP.
- Local demo configuration is documented without exposing credentials.

## CLEAN-P1-05 Teammate-Ready Commit Scope

### Recommended First PR

Create an MVP stabilization PR with this scope:

- POSTSEARCH-STAB-P0 verification baseline results.
- MVP marketplace/auth/listing/search/chat/admin fixes already completed.
- Documentation updates that match the actual MVP code state.
- No V2 cart/inventory/order/address implementation.
- No V3 AI implementation.
- No local `.env` secrets.

### Suggested Verification

Run:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false
npm.cmd run build
cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

### Recommended Follow-Up PRs

After the MVP stabilization PR:

- V2 commerce quarantine PR: cart, inventory, order, and address-book work.
- AI quarantine PR: agent service and listing knowledge publication.
- Business portal continuation PR: only after marketplace-first MVP flow is
  stable enough for teammate development.

## Next Action

The next concrete cleanup slice should be:

`CLEAN-P1-01A MVP Stage Dry Run`

Goal: list the exact files and hunks that belong in the MVP stabilization PR
without staging them yet. This should produce a final pre-commit checklist for
the team.
