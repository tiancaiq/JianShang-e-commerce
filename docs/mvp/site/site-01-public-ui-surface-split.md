# SITE-01 Public UI Surface Split

Status: complete.

## Goal

Separate buyer-facing public discovery into two UI surfaces:

- individual marketplace
- business item shopping

This keeps individual trade browsing visually and semantically separate from
business storefront browsing while still using one Angular app.

## Scope

Implemented routes:

```text
/
/marketplace
/stores
```

Rules:

- `/` and `/marketplace` render the individual marketplace experience.
- The marketplace page filters the current public listing feed to
  `sellerType=INDIVIDUAL`.
- `/stores` renders a business item browsing surface using current approved
  public business listings as product cards with safe business seller display
  metadata.
- Public navigation now exposes `Marketplace`, `Stores`, and `Sell`.
- Admin links remain hidden from public navigation.
- V2 commerce concepts remain absent from active public navigation and store
  actions.

## Out Of Scope

- New backend storefront APIs.
- Store detail pages.
- Store slugs.
- Cart, checkout, inventory, payment, orders, shipping, and notifications.
- Chat/contact seller.
- OpenSearch.

## Follow-Up Slices

- `SEARCH-01A`: backend keyword/filter/sort search for individual marketplace listings.
- `SEARCH-01B`: business storefront search.
- Future storefront slices: one-store detail/scoped browse APIs and UI.
- `SEARCH-03`: shared filters, sorting, and cursor pagination.

## Verification

Expected local checks:

```powershell
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```
