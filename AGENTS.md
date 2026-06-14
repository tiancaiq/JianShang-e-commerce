# AGENTS.md

## Scope

These instructions apply to the entire repository.

The approved MVP specification is:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`

When older README or tutorial documents conflict with these files, the MVP
documents take precedence.

## Product Invariants

1. Individual listings create **trades**, not platform orders.
2. Individual buyers and sellers arrange payment and delivery themselves.
3. The platform must not claim to verify or protect off-platform payment.
4. Business listings use cart, inventory reservation, platform payment, order,
   and shipping.
5. Buyer, individual seller, business staff, and admin access share one user
   identity with scoped roles.
6. Business access always checks `businessId` membership and permission.
7. AI is optional and cannot bypass authorization, validation, or confirmation.

Do not merge the individual trade and business order state machines.

## Implementation Workflow

Before changing code:

1. Select one requirement or roadmap slice.
2. Read its requirement ID, API contract, database ownership, and dependencies.
3. Inspect the existing implementation and uncommitted changes.
4. State any contract ambiguity before inventing behavior.

For each slice:

1. Add or update a forward-safe Flyway migration when persistence changes.
2. Implement backend domain behavior and authorization.
3. Update OpenAPI/API client types.
4. Implement the smallest frontend path needed for the slice.
5. Add unit, integration, authorization, and relevant end-to-end tests.
6. Add structured logs and metrics for important failure paths.
7. Update MVP documents when an approved contract changes.

Do not implement multiple roadmap slices in one change unless they are
inseparable and the reason is documented.

## Repository Direction

- Java 21 and Spring Boot remain the backend baseline.
- Angular is the MVP frontend framework.
- MySQL is the default transactional database for new MVP data.
- Redis stores carts, rate limits, and other temporary state.
- Kafka carries durable domain events.
- OpenSearch is a derived listing-search index.
- S3-compatible object storage holds listing media.
- OpenAI access occurs only through the isolated agent service.

Avoid adding PostgreSQL, MongoDB, RabbitMQ, Kubernetes, or another frontend
framework to new MVP paths without an approved architecture decision.
Existing technology may remain while a documented migration is in progress.

## Service Ownership

- A service owns its schema.
- Never query or update another service's database.
- Use synchronous APIs for immediate validation/commands.
- Use Kafka events for asynchronous projections and notifications.
- Publish events through a transactional outbox.
- Consumers deduplicate by event ID.

Do not create a new microservice only to hold one table or endpoint. Begin with
a clear module boundary and split deployment only for measured scaling,
ownership, reliability, or release reasons.

## API Rules

- External routes use `/api/v1`.
- Follow `docs/mvp/api-contract.md`.
- Use the standard error envelope and correlation ID.
- Use cursor pagination for unbounded collections.
- Never trust client prices, totals, roles, user IDs, business IDs, or payment
  status.
- Require `Idempotency-Key` for retryable money, inventory, checkout, order,
  shipping, and webhook operations.
- Use optimistic locking for seller/admin edits and state decisions.
- Keep backward compatibility or version the contract.

## Database Rules

- Use Flyway; do not rely on Hibernate schema auto-update.
- Use InnoDB, UTC, and `utf8mb4`.
- Store money as decimal plus currency.
- Keep status values stable and explicit.
- Add indexes for actual query and queue patterns.
- Keep immutable history for offers, order state, payments, moderation, and
  audit actions.
- Use address, price, listing, and policy snapshots for orders.
- Never store raw card data, passwords, access tokens, or external bank
  credentials.
- Redis and OpenSearch are never authoritative for orders, payments, trades,
  or audit records.

## Security Rules

- Enforce authorization in backend services, not only the gateway or UI.
- Test cross-user and cross-business access for every protected resource.
- Admin operations use granular roles and produce audit records.
- Verify webhook signatures and deduplicate provider event IDs.
- Redact secrets, tokens, payment details, and unnecessary PII from logs.
- Treat chat and uploaded media as untrusted input.
- Do not expose exact individual meeting/home locations publicly.

## AI Rules

- Agents call allowlisted application tools; they do not access databases.
- Tool calls execute with the requesting actor's permissions.
- Validate tool arguments with strict schemas.
- Draft actions require human confirmation before persistence.
- AI cannot approve businesses, publish listings, charge payments, issue
  significant refunds, suspend users, or settle disputes in MVP.
- Log safe prompt metadata, tool calls, result status, latency, and cost.
- Core marketplace flows must work when AI is disabled or unavailable.

## Testing Expectations

Minimum per slice:

- Unit tests for domain rules and state transitions
- Integration tests for persistence and API behavior
- Authorization/tenant-isolation tests
- Idempotency tests for retryable commands
- Event publication/consumer deduplication tests when Kafka is involved
- Frontend component/service tests for user-visible behavior

Additional mandatory tests:

- Concurrent inventory reservation and offer acceptance
- Payment webhook replay and payment/order recovery
- Listing visibility after moderation/suspension
- Chat participant and admin evidence authorization
- AI tool authorization and prompt-injection resistance

Use Testcontainers for database, Redis, and Kafka integration where practical.

## Change Safety

- Preserve unrelated user changes in the working tree.
- Do not rewrite existing migrations that may have run; add a new migration.
- Do not perform broad refactors while implementing a small requirement.
- Do not silently change an approved product invariant.
- Feature-flag incomplete workflows so users cannot enter dead ends.
- Prefer adapters around external payment, shipping, email, storage, and AI
  providers.

## Completion Report

When finishing a slice, report:

- Requirement/roadmap IDs completed
- Files and contracts changed
- Migrations added
- Tests run and results
- Known limitations or deferred dependencies
