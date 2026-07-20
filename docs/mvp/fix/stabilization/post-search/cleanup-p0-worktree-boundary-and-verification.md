# CLEAN-P0 Worktree Boundary And Verification

Status: complete.

Date: 2026-07-19.

## Goal

Create a clean safety baseline for the current dirty worktree before staging,
parking, deleting, or continuing product work.

This slice does not change product behavior. It records what exists, separates
MVP work from V2/V3 work, and identifies safe cleanup candidates.

## P0-01 Git Safety Snapshot

The worktree is not clean. `git status --short` shows modified files across:

- repository configuration: `.env`, `.gitignore`, GitHub Actions, Docker
  Compose, `AGENTS.md`, root `pom.xml`, and `run.sh`
- MVP services: `api-gateway`, `auth-service`, `product-service`, and
  `chat-service`
- frontend marketplace, auth, account, business, listing, store, chat, and
  layout areas
- V2 services: `inventory-service` and `order-service`
- MVP and V2 documentation

There are also untracked directories and files:

- `.playwright-mcp/`
- `agent-service/`
- `docs/mvp/ai/`
- `docs/v2/`
- V2 address, cart, order, and inventory source files
- business/search docs added after the previous cleanup
- listing knowledge/publication source files
- local marketplace image screenshots
- local seed/debug tools

No code was deleted during this slice.

## P0-02 Local Config Classification

Tracked environment files:

- `.env`
- `.env.dev`
- `.env.example`
- `.env.demo.example`

Untracked or ignored local environment files:

- `.env.local`
- `.env.demo`

Risk notes:

- `.env` is tracked and currently modified. It contains development credential
  variables and local Keycloak/client-secret placeholders. Treat it as
  commit-risky until reviewed line by line.
- `.env.local` contains local-only provider keys and must remain untracked.
- `.env.demo` contains demo/local secrets and must remain untracked.
- Safe values should live in `.env.example` or `.env.demo.example`.
- Real or machine-specific secrets should stay in local ignored files or a
  deployment secret manager.

Recommended follow-up:

- Move any reusable local-development placeholder values from `.env` into the
  example files.
- Revert or untrack real local changes in `.env` only after confirming the
  local demo no longer depends on them.

## P0-03 MVP Boundary Classification

MVP-active work to keep in the next MVP stabilization branch:

- authentication/session/login stabilization
- user profile/avatar behavior required by marketplace account flows
- individual listing lifecycle and media
- public marketplace search and listing detail
- business storefront search and business item management already approved for
  MVP
- basic buyer/seller chat and conversation-gated done flow currently marked
  complete by the roadmap
- admin moderation and reactive removal paths

V2 work to park or stage separately:

- cart UI/API/service files
- Redis cart validation
- inventory initialization, reservation, release, and commit
- buyer address book
- order/checkout/payment/shipping preparation

V3 or experimental work to park or stage separately:

- `agent-service/`
- `docs/mvp/ai/`
- listing embedding and RAG ingestion/indexing code
- OpenAI provider runtime wiring unless the team explicitly promotes AI back
  into the active demo scope

Do not mix MVP stabilization with V2 commerce or AI/RAG changes in one PR.

## P0-04 Artifact Cleanup Candidates

Safe ignore candidates identified:

- `.playwright-mcp/`
- `marketplace-images-*.png`
- existing ignored runtime/build output under `.runtime-logs/`, `logs/`,
  `target/`, `frontend/.angular/`, and `frontend/dist/`

This slice updated `.gitignore` only for `.playwright-mcp/` and
`marketplace-images-*.png`. No local artifact files were deleted.

Deletion candidates requiring explicit confirmation:

- local screenshots: `marketplace-images-after-env-fix.png`,
  `marketplace-images-fixed.png`
- Playwright MCP output under `.playwright-mcp/`
- older local root service logs if still present

## P0-05 Verification Results

Verification was run after the inventory and ignore cleanup.

Initial results before fixes:

- Frontend build: passed.
  - Command: `npm.cmd run build`
  - Output path: `frontend/dist/frontend`
- Frontend unit tests: failed.
  - Command: `npm.cmd test -- --watch=false --browsers=ChromeHeadless`
  - Summary: `329` specs executed, `319` passed, `10` failed.
  - Failure cluster: `MarketplaceLayoutComponent` specs all fail while
    constructing `FloatingChatComponent`.
  - Shared error: `TypeError: Cannot read properties of undefined (reading
    'pipe')` at `frontend/src/app/features/chat/floating-chat.component.ts`.
  - Captured local log:
    `logs/cleanup-p0-frontend-test.log`.
- MVP backend tests: failed.
  - Command:
    `cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test`
  - Reactor reached `product-service`; `chat-service`, `api-gateway`, and
    `auth-service` were skipped after the product-service failure.
  - Passing before failure: `common-core`, `common-web`, `common-storage`, and
    `common-testing`.
  - Failure cluster:
    `com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxPublisherTests`
    has `2` errors.
  - Shared error: Mockito inline mocking cannot mock
    `ListingKnowledgeRepository` because the current Java runtime is Java 25
    and the current Byte Buddy version officially supports Java 24.
  - Detailed report:
    `product-service/target/surefire-reports/com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxPublisherTests.txt`.

Fixes applied:

- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
  now gives the `ChatService` test double the same observable and completion
  methods used by the real `FloatingChatComponent`.
- Root `pom.xml` configures the test JVM with
  `net.bytebuddy.experimental=true` so Mockito/Byte Buddy can run under the
  local Java 25 runtime. Production runtime behavior is unchanged.

Final verification results:

- Frontend unit tests: passed.
  - Command: `npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false`
  - Summary: `329` specs executed, `329` passed.
- Frontend build: passed.
  - Command: `npm.cmd run build`
- MVP backend tests: passed.
  - Command:
    `cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test`
  - Reactor result: `common-core`, `common-web`, `common-storage`,
    `common-testing`, `product-service`, `chat-service`, `api-gateway`, and
    `auth-service` all passed.

Remaining verification note:

- Maven still prints Java 25 warnings from Mockito dynamic self-attachment and
  JDK restricted/deprecated APIs. They no longer fail the build. A later
  dependency/toolchain cleanup should either standardize local Java 21 or move
  Mockito to the recommended Java-agent setup.

Environment note:

- The first sandboxed verification attempt failed before meaningful project
  results because PowerShell blocked `npm.ps1`, Angular could not read sandboxed
  paths, and the Maven wrapper did not start through the sandbox wrapper.
- The reported pass/fail results above are from reruns using `.cmd` entry
  points outside the sandbox.

## Recommended Next Step

After this P0 baseline is confirmed, create separate cleanup/staging PRs:

1. MVP stabilization only.
2. V2 commerce work, parked or staged separately.
3. AI/RAG work, parked or staged separately.
4. Local config cleanup for `.env` and demo environment files.
