# PRESEARCH-STAB-P1-04 Backend Listing Service Readability Cleanup

Status: complete.

## Goal

Make completed product-service listing behavior easier to review before adding
search query behavior.

This cleanup preserves listing API behavior and persistence.

## Scope

Review completed backend listing code for:

- draft commands
- edit and resubmit rules
- close rules
- media attachment
- moderation decisions
- public browse
- public detail
- authorization checks

## Tasks

- Extract small private methods only where they clarify existing rules.
- Add concise purpose comments for non-obvious authorization or state helpers.
- Preserve transaction boundaries.
- Preserve repository query results.
- Preserve DTO fields and response status codes.

## Non-Goals

- No new repository query behavior.
- No search filters or pagination.
- No schema changes.
- No lifecycle changes.
- No API changes.

## Verification

```powershell
.\mvnw.cmd -pl product-service -am test
```

## Acceptance Criteria

- Listing service behavior and API output are unchanged.
- Product-service tests pass.
- The next search PR can focus on read-query behavior.

## Completion Notes

- Extracted a small seller-ID collection helper for listing seller label
  lookups.
- Kept repository queries, transaction boundaries, DTO fields, endpoints, and
  lifecycle behavior unchanged.
- No schema or API contract changes were made.

## Verification Results

```powershell
.\mvnw.cmd -pl product-service -am test
```

Result: `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0`.
