# SEARCH-03 Shared Filters, Sorting, And Cursor Pagination

Status: complete.

## Scope

SEARCH-03 stabilizes the database-backed public search contracts before
OpenSearch is introduced.

It adds cursor pagination to:

- `GET /api/v1/public/marketplace/listings/search`
- `GET /api/v1/public/stores/listings/search`

It does not add OpenSearch, cart, checkout, inventory, orders, payment,
shipping, chat, or structured offers.

## Contract

Both split public search endpoints accept the existing filters plus:

```text
cursor
limit
```

Responses use the paged collection shape:

```json
{
  "data": [],
  "page": {
    "nextCursor": "opaque-or-null",
    "hasMore": false
  }
}
```

Rules:

- `cursor` is opaque to clients.
- `limit` is optional and capped by the server.
- Missing `sort` uses the default stable order.
- Sort modes remain `newest`, `price_asc`, and `price_desc`.
- Listing detail remains the final public-visibility revalidation point.

## Backend

- Added a public listing search page response DTO.
- Added cursor and limit request handling for the split search endpoints.
- Kept `/api/v1/public/listings` as the legacy fixed-size array response.
- Added deterministic cursor predicates for:
  - newest: published timestamp, then listing ID
  - price low/high: price, published timestamp, then listing ID
- Added integration coverage for cursor pagination on individual marketplace
  and business storefront search.

## Frontend

- Angular listing service now consumes the paged response for split public
  search endpoints.
- Marketplace and Stores pages keep their existing filters and append results
  through a `Load more` button.
- Existing listing cards and detail navigation are unchanged.

## Deferred

- OpenSearch projection remains `SEARCH-04`.
- One-store scoped storefront pages remain deferred.
- Search analytics, ranking, suggestions, and typo tolerance are deferred.
