# POSTSEARCH-STAB-P2-03 Historical Demo And Deferred Module Cleanup

Status: Complete  
Date: 2026-07-19

## Goal

Make the active MVP implementation, deferred commerce work, AI work, and
historical tutorial code easy to distinguish without deleting, moving, or
reactivating application code.

This is a documentation-only cleanup. No application behavior, routes,
Maven modules, runtime configuration, or environment files were changed.

## Decision

Leave deferred and historical folders indexed in place for now.

Do not archive or move them during the current cleanup because:

- `inventory-service` and `order-service` contain substantial uncommitted V2
  implementation work.
- V2 changes also cross mixed files in the gateway, auth service, product
  service, frontend, root Maven reactor, and MVP source-of-truth documents.
- Moving folders now would create noisy rename/delete changes and increase the
  chance of losing or mixing teammate work.
- The existing V2 and AI quarantine checklists already define safer future
  branch boundaries.

Folder moves or deletions require a later focused PR after the MVP, V2, and AI
changes have been separated into clean branches.

## Current Active MVP Baseline

The following modules are the active MVP baseline:

- `common-core`
- `common-web`
- `common-storage`
- `common-testing`
- `api-gateway`
- `auth-service`
- `product-service`
- `chat-service`
- `frontend`

The active MVP frontend route families are:

- Public marketplace: `/`, `/marketplace`, `/listings/:listingId`
- Public business stores: `/stores`, `/stores/:storeSlug`
- Marketplace account: `/account`, `/account/profile`, `/account/liked`,
  `/account/seller-profile`, `/account/messages`, and `/account/listings`
- Business seller portal: `/seller`, business application, store profile, and
  store item management routes
- Admin portal: business application and listing moderation routes

This list describes the MVP review boundary. It does not mean every route in
the current dirty worktree belongs to the MVP.

## V2 Commerce Work Present In The Worktree

The following V2 implementation is present and must remain outside an
MVP-only stabilization PR:

- `inventory-service/`
- `order-service/`
- `docs/v2/commerce/`
- Frontend cart models, service, component, and tests
- Frontend address-book models, service, component, and tests
- Frontend business inventory models, service, component, and tests
- Gateway cart, inventory, and address-book route changes
- Auth address-book and internal commerce eligibility changes
- Product internal commerce context changes
- Root Maven entries for `inventory-service` and `order-service`

The detailed file-level boundary remains:

- `docs/mvp/fix/stabilization/post-search/cleanup-p1-02-v2-quarantine-checklist.md`

## Frontend Route Audit

The current frontend does not satisfy a pure MVP-only navigation boundary.
The audit found:

| Route or screen | Current state | Release boundary |
| --- | --- | --- |
| `/cart` | Active authenticated route and linked from the marketplace navbar | V2 |
| `/account/addresses` | Active authenticated route and linked from the account dashboard | V2 |
| `/seller/inventory` | Disabled by the default feature flag; navigation is hidden and the route redirects to store items | V2 |
| Legacy `/orders` screens | Files remain, but no active route is registered | Historical tutorial |
| Legacy `/payments` screens | Files remain, but no active route is registered | Historical tutorial |
| Legacy generic `/inventory` screen | File remains, but no active route is registered | Historical tutorial |
| Legacy dashboard/product screens | Files remain, but no active route is registered | Historical tutorial |

Therefore, `/cart` and `/account/addresses` must be excluded through the
existing hunk-staging plan when preparing the MVP PR. This slice does not
silently remove those implemented V2 flows from the user's worktree.

## Historical Backend Stubs

`payment-service/` and `notification-service/` remain tracked tutorial-era
service folders.

They are not root Maven reactor modules and are not active frontend route
dependencies. Keep them out of MVP build, deployment, and API claims. A later
archive PR may move them only after repository branches are clean and the team
confirms no remaining reference is needed.

## Historical Frontend Folders

These tracked folders are not imported by the active route table:

- `frontend/src/app/features/dashboard/`
- `frontend/src/app/features/inventory/`
- `frontend/src/app/features/orders/`
- `frontend/src/app/features/payments/`
- `frontend/src/app/features/products/`
- `frontend/src/app/layout/sidebar/`

They may remain as historical code for now. New MVP code must not import from
them. Deletion or archival belongs in a separate PR after the active branches
are separated.

## AI And V3 Boundary

AI, RAG, category-guidance, and agent-service changes are a separate V3
boundary and are not part of this V2/tutorial cleanup. Use:

- `docs/mvp/fix/stabilization/post-search/cleanup-p1-03-ai-quarantine-checklist.md`

The current worktree includes AI/V3 route and runtime changes, so it must not
be described as an MVP-only runtime without applying that quarantine plan.

## Teammate Rules

- Use the MVP module and route list in this document for an MVP review.
- Do not use `git add .` in the current mixed worktree.
- Follow the V2 quarantine checklist for whole-file and hunk exclusions.
- Follow the AI quarantine checklist for agent, knowledge, CI, and runtime
  exclusions.
- Do not build new MVP behavior on tutorial order, payment, inventory,
  notification, dashboard, or generic product screens.
- Do not edit or stage `.env` or other local environment files as part of
  cleanup.

## Verification

The cleanup was based on these read-only checks:

```powershell
rg -n "inventory|orders|payments|notification|deferred|tutorial|active MVP|cart|addresses" docs frontend/src/app
rg -n "<module>|inventory-service|order-service|payment-service|notification-service|agent-service" pom.xml .github/workflows/pull-request-quality.yml docker-compose.yml docker-compose.demo.yml
rg -n "routerLink=.?/cart|routerLink=.?/account/addresses|Cart|Addresses|inventory" frontend/src/app/layout frontend/src/app/features/account frontend/src/app/features/marketplace frontend/src/app/features/seller frontend/src/app/features/business --glob "*.ts"
git ls-files payment-service notification-service frontend/src/app/features/dashboard frontend/src/app/features/inventory frontend/src/app/features/orders frontend/src/app/features/payments frontend/src/app/features/products frontend/src/app/layout/sidebar
```

Documentation validation:

```powershell
git diff --check -- docs AGENTS.md
```

No application tests were run because this slice changes documentation only.

## Acceptance Result

- Active MVP modules and routes are explicitly listed.
- V2 implementation, V3/AI work, and historical tutorial code have separate
  review boundaries.
- The currently exposed V2 cart and address-book routes are documented rather
  than incorrectly described as inactive.
- Historical tutorial screens have no active route registration.
- No V2 behavior was added, removed, or reactivated.
- No files were deleted, moved, staged, or archived.
- No environment files were edited.
