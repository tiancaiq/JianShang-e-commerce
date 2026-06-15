# P1-09 Frontend Workspace Plan

## Purpose

This plan defines the target Angular workspace structure for the MVP web
frontends while preserving the current `frontend` application entry point.

P1-09 does not create feature pages, migrate existing screens, change routing,
or implement authentication/listings/chat/admin behavior.

## Current State

The repository currently has one Angular application:

```text
frontend/
  src/
    app/
      core/
      features/
      layout/
      shared/
```

The Angular project is named `frontend` in `frontend/angular.json`. It remains
the runnable application until a later approved migration slice creates the
multi-app workspace.

Current app role:

- Keep as the existing development entry point.
- Keep existing routes and tests working.
- Do not move current feature files during P1-09.

## Target Workspace

The target workspace is:

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

Target applications:

| App | Audience | Primary MVP responsibility |
| --- | --- | --- |
| `marketplace` | guests, buyers, individual sellers | public storefront, search, individual seller shared site, buyer/seller chat entry points |
| `seller-portal` | business owners and staff | business profile, store profile, business listings, business staff management |
| `admin-portal` | platform staff | business/listing moderation and basic admin review |

Deployment targets:

| Host | App |
| --- | --- |
| `www.example.com` | `marketplace` |
| `seller.example.com` | `seller-portal` |
| `admin.example.com` | `admin-portal` |

The admin portal must be separately deployed and must not be linked from
public marketplace navigation.

## Shared Libraries

Shared libraries should contain transport, UI, validation, and infrastructure
code only. They must not contain app-owned pages or feature workflows.

| Library | Purpose | Allowed examples | Not allowed |
| --- | --- | --- | --- |
| `auth` | frontend auth state and route protection adapters | OIDC client adapter, role/claim helpers, guards | backend authorization rules, app-specific login pages |
| `api-client` | generated or typed API access | OpenAPI clients, interceptors, correlation ID propagation | hand-written domain business decisions |
| `models` | shared TypeScript transport models | API DTOs, enums, pagination/error types | mutable Angular services or feature state |
| `ui` | reusable design components | buttons, forms, cards, dialogs, loading/error states | marketplace/seller/admin page flows |
| `validation` | reusable form validation helpers | email, money, URL, image file checks | persistence validation or trust decisions |
| `observability` | client diagnostics conventions | frontend correlation ID, logging adapter, metrics hooks | secrets, direct vendor-specific business logic |

## App Boundaries

### Marketplace

Owns public and buyer-facing composition:

- homepage/search shell
- public listing/storefront shell
- individual seller public profile shell
- buyer conversation entry shell

It may use shared libraries but must not import `seller-portal` or
`admin-portal` code.

### Seller Portal

Owns business seller management composition:

- business/store profile shell
- business listing management shell
- staff/membership shell

Every data access path must be scoped by `businessId` at the backend. The
frontend can improve UX, but it is never the source of authorization truth.

### Admin Portal

Owns platform staff workflows:

- moderation queue shell
- business application decision shell
- internal notes shell

The admin portal must treat all loaded content as sensitive operational data.
Do not expose admin links or code paths through public navigation.

## Migration Strategy

Use small follow-up slices. Do not move everything at once.

1. Keep current `frontend` project buildable.
2. Create empty buildable app shells only when a slice needs them.
3. Create shared libraries one at a time when a real caller exists.
4. Move route groups by app boundary, not by file type.
5. Replace imports with library entry points only after tests pass.
6. Keep feature routes behind app-owned route files.
7. Remove the legacy single-app route only after equivalent app shell behavior
   is verified.

Recommended future Angular CLI commands:

```powershell
ng generate application marketplace --routing --style css --ssr=false
ng generate application seller-portal --routing --style css --ssr=false
ng generate application admin-portal --routing --style css --ssr=false
ng generate library auth
ng generate library api-client
ng generate library models
ng generate library ui
ng generate library validation
ng generate library observability
```

Run these in `frontend/` during the approved migration slice. Adjust SSR only
after the deployment strategy is confirmed.

## Import Rules

Allowed direction:

```text
apps/* -> libs/*
libs/* -> other lower-level libs only when necessary
```

Disallowed:

```text
libs/* -> apps/*
apps/marketplace -> apps/seller-portal
apps/marketplace -> apps/admin-portal
apps/seller-portal -> apps/admin-portal
apps/admin-portal -> apps/seller-portal
```

Suggested library dependency direction:

```text
auth -> models, api-client
api-client -> models, observability
ui -> models
validation -> models
observability -> models
```

Keep dependencies boring and explicit. If a library starts importing many
other libraries, split the use case back into the app until the boundary is
clear.

## Naming Conventions

- App route files: `*.routes.ts`
- Page-shell components: `*.page.ts`
- Reusable UI components: `*.component.ts`
- Injectable services: `*.service.ts`
- Guards: `*.guard.ts`
- Interceptors: `*.interceptor.ts`
- Public library exports: `public-api.ts`

Use standalone components and lazy route loading by default.

## Testing Expectations

For this setup slice:

- Existing frontend build still passes.
- Existing frontend tests still pass.

For future shell-creation slices:

- Each created app shell builds.
- Each created library compiles.
- Each app has at least one smoke test verifying it boots.
- Route guard tests cover unauthorized access once auth is implemented.

## Completion Boundary

P1-09 creates a migration plan only. It does not:

- create the three app shells
- create Angular libraries
- move current routes
- change visual layout
- add auth, listings, chat, seller, or admin features
- change backend APIs
