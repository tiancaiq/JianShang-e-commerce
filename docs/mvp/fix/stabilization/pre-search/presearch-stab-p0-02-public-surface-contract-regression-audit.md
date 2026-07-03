# PRESEARCH-STAB-P0-02 Public Surface Contract Regression Audit

Status: complete.

## Goal

Lock down the completed public UI surface split before search adds new query
behavior.

This cleanup preserves the current public routes and visible behavior.

## Scope

Completed routes reviewed:

- `/`
- `/marketplace`
- `/stores`
- `/listings/{listingId}`
- `/account/listings`
- `/account/listings/new`
- `/account/listings/{listingId}/edit`

## Tasks

- Verify marketplace navigation keeps public users on marketplace/store/sell
  surfaces.
- Verify `/` and `/marketplace` remain individual marketplace surfaces.
- Verify `/stores` remains the business store surface.
- Verify public listing detail remains guest-readable only for approved active
  listings.
- Add or tighten regression tests only for completed behavior.
- Update docs only where route ownership is unclear.

## Changes

Frontend route tests:

- Added route-component assertions that lazy-load and verify:
  - `/` uses `MarketplaceHomeComponent`
  - `/marketplace` uses `MarketplaceHomeComponent`
  - `/stores` uses `BusinessStoresComponent`
  - `/listings/:listingId` uses `PublicListingDetailComponent`
- Verified these public route children remain unguarded.

Marketplace layout tests:

- Tightened public navigation checks so `Marketplace`, `Stores`, and `Sell`
  point to the expected URLs:
  - `/marketplace`
  - `/stores`
  - `/account/listings/new`
- Existing checks continue to prove admin and V2 commerce navigation is absent
  from the public marketplace header.

## Files Changed

- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
- `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-02-public-surface-contract-regression-audit.md`
- `docs/mvp/fix/stabilization/pre-search/fix-04-pre-search-stabilization-sprint.md`
- `docs/mvp/development-roadmap.md`

## Behavior

No runtime route, component, visible copy, API, or database behavior changed.

## Non-Goals

- No new search endpoint.
- No store detail route.
- No filters, sorting, or cursor pagination.
- No cart, checkout, inventory, payment, order, shipping, or chat behavior.
- No visual redesign.

## Verification

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/app.routes.spec.ts --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/features/marketplace/public-listing-detail.component.spec.ts
```

Actual command:

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/app.routes.spec.ts --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/features/marketplace/public-listing-detail.component.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Result:

```text
TOTAL: 24 SUCCESS
```

## Acceptance Criteria

- Public route ownership is covered by focused tests.
- Individual marketplace and business store surfaces stay separate.
- No visible behavior, route, API, or data contract changes were introduced.
