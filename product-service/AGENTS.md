# Product Service Instructions

These rules apply under `product-service/` in addition to the repository
instructions.

## Ownership and domain boundaries

- This service owns listing, listing-media, moderation, and Product-owned
  search/projection behavior defined by the approved slice.
- Individual listings participate in trades, never business orders. Keep
  individual and business listing behavior explicit and do not introduce V2
  inventory or order semantics into an MVP listing slice.
- Listing media rules stay here; `common-storage` provides only technical
  object-storage adapters.
- Public results contain only approved, visible listings and public-safe seller
  or location fields. Treat uploaded media and listing content as untrusted.

## API, authorization, and concurrency

- External endpoints use `/api/v1`, the standard error envelope, correlation
  IDs, and cursor pagination for unbounded collections.
- Never trust client prices, status, ownership, roles, user IDs, or business
  IDs. Enforce listing ownership, `businessId` membership/permission, and admin
  moderation authorization in this service.
- Use optimistic locking for seller edits and moderation decisions. Preserve
  compatibility or version intentional contract changes.
- Admin decisions use granular roles and immutable audit context.

## Persistence and projections

- Use forward-only Flyway migrations and MySQL/InnoDB with UTC, `utf8mb4`,
  stable statuses, and indexes for real listing/search/moderation queries.
- Store listing money as decimal value plus currency; never derive an
  authoritative price from client input or a search projection.
- Keep immutable moderation and listing-state history where the contract
  requires it.
- MySQL is authoritative. OpenSearch is a rebuildable derived projection and
  must never establish listing visibility, ownership, price, or state by
  itself.
- Publish approved durable projection events through an outbox and deduplicate
  consumers by event ID.

## Verification

Test domain transitions, persistence/API behavior, listing visibility,
cross-user and cross-business isolation, moderation authorization, optimistic
locking, and projection failure or stale-data handling. Test event publication
and deduplication when Kafka is involved; use Testcontainers for MySQL and
OpenSearch integration where practical.
