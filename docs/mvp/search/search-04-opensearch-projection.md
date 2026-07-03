# SEARCH-04 OpenSearch Projection

Status: complete.

## Scope

SEARCH-04 adds an OpenSearch-backed derived projection for approved public
listing search.

It does not change the public search response shape and does not make
OpenSearch authoritative for listing visibility. MySQL remains the source of
truth.

Implemented endpoints remain:

```text
GET /api/v1/public/marketplace/listings/search
GET /api/v1/public/stores/listings/search
```

An admin rebuild endpoint is added:

```text
POST /api/v1/admin/search/listings/rebuild
```

## Behavior

- `listing.search.engine=mysql` is the default.
- `listing.search.engine=opensearch` enables the OpenSearch read path.
- OpenSearch search returns candidate listing IDs only.
- Product service reloads those IDs from MySQL before returning public cards,
  preserving final visibility checks and safe public projection rules.
- Listing approve, admin edit, seller edit, seller close, submit for review,
  and admin removal update or delete the derived projection on a best-effort
  basis.
- If OpenSearch search is enabled and unavailable, public search returns
  `503 LISTING_SEARCH_UNAVAILABLE`.
- Listing detail remains MySQL-backed.

## Configuration

```properties
LISTING_SEARCH_ENGINE=mysql
OPENSEARCH_URL=http://localhost:9201
OPENSEARCH_LISTING_INDEX=msb-public-listings
OPENSEARCH_CONNECT_TIMEOUT=PT1S
OPENSEARCH_REQUEST_TIMEOUT=PT3S
OPENSEARCH_INITIALIZE_INDEX=true
```

Local compose includes OpenSearch on host port `9201` and OpenSearch
Dashboards on `5602`, avoiding the existing Elasticsearch/Kibana ports.

## Projection Fields

The index stores only safe searchable public fields:

- listing ID
- seller type
- category ID, slug, and name
- title and description
- condition
- price amount and currency
- public city/county keys
- published timestamp

It does not store owner private IDs in returned payloads, internal moderation
state, media bucket/key, private contact data, exact individual locations,
orders, payment, inventory, or checkout data.

## Deferred

- Kafka/outbox-driven projection updates.
- Bulk `_bulk` rebuild optimization.
- Store-slug scoped storefront pages.
- Suggestions, typo tolerance, analytics, and ranking.
