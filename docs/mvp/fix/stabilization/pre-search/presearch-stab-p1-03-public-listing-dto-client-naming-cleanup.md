# PRESEARCH-STAB-P1-03 Public Listing DTO And Client Naming Cleanup

Status: complete.

## Goal

Make public listing code names match the split between individual marketplace
and business store surfaces without changing wire contracts.

This cleanup is naming and organization only.

## Scope

Review names around:

- public listing response DTOs
- Angular listing models
- listing service helper names
- listing test fixtures
- business store grouping helpers

## Tasks

- Rename internal TypeScript aliases, helpers, or fixtures when names imply the
  wrong product surface.
- Keep Java JSON field names unchanged.
- Keep endpoint paths unchanged.
- Keep public response shapes unchanged.
- Do not split backend APIs in this cleanup.

## Non-Goals

- No API contract change.
- No database change.
- No new endpoint.
- No public search or storefront behavior.

## Verification

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
```

## Acceptance Criteria

- Code naming is easier to understand.
- Public listing API wire shape is unchanged.
- Existing tests pass.

## Completion Notes

- Kept the backend `PublicListingResponse` DTO and all JSON field names
  unchanged.
- Added Angular aliases for current public surfaces:
  `MarketplaceBrowseListing` and `BusinessStoreListing`.
- Updated marketplace and business store components to use surface-specific
  names for filtered UI data.
- No API paths, response fields, backend DTOs, or behavior changed.

## Verification Results

```powershell
cd frontend
npm.cmd test -- --watch=false --include=src/app/features/marketplace/marketplace-home.component.spec.ts --include=src/app/features/stores/business-stores.component.spec.ts --include=src/app/core/services/listing.service.spec.ts
```

Result: `TOTAL: 33 SUCCESS`.
