# POSTSEARCH-STAB-P1-02 Backend Search And Listing Service Readability Cleanup

Status: complete.

## Goal

Make backend public search code easier to review before chat/admin expansion
without changing product behavior, API responses, route paths, database schema,
or OpenSearch behavior.

## Changes

- Extracted public listing search request parsing into
  `PublicListingSearchRequests`.
- Moved search limit normalization and cursor encode/decode into the same
  focused helper.
- Kept `ListingService` responsible for transaction flow, public listing page
  assembly, seller labels, media attachment, moderation, and projection calls.
- Added concise comments for non-obvious rules:
  - OpenSearch returns candidate IDs only.
  - MySQL revalidates public visibility before response data is returned.
  - Projection failures do not roll back authoritative listing transactions.

## Behavior

No behavior change is intended.

- Marketplace search still returns approved active individual listings.
- Business storefront search still returns approved active business listings.
- Cursor format, validation messages, and page metadata are unchanged.
- OpenSearch remains a derived projection.
- MySQL remains authoritative for public listing visibility.

## Verification

Passed:

```powershell
.\mvnw.cmd -pl product-service -am test
```

Result:

- Reactor build passed for `common-core`, `common-web`, and
  `product-service`.
- Product-service tests: 102 run, 0 failures, 0 errors.

## Non-Goals

- No endpoint or JSON field rename.
- No SQL query behavior change.
- No frontend changes.
- No storage, avatar, chat, cart, checkout, order, notification, review, AI, or
  analytics behavior.
