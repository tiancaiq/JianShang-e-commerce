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

## Current Implementation Boundary

Phase 0 and Phase 1 setup tasks in `docs/mvp/development-roadmap.md` are
complete. MVP feature work is now active and must still be implemented one
small roadmap slice at a time.

Before implementing a slice, read the current roadmap entry and related MVP
documents. Do not assume an older README, tutorial note, or stale plan is the
active source of truth.

The three MVP product surfaces are:

- Public marketplace site: guests can browse/search/view approved goods
  without login; signed-in users can manage profile, sell individual items,
  and chat.
- Business seller portal: approved merchants manage store profile and basic
  listings in MVP.
- Admin portal: platform staff manage business approval and listing
  moderation in MVP.

Release placement:

- MVP: foundation, accounts, seller profiles, listings/media, guest browsing,
  search/storefront, basic chat, basic business/listing moderation
- V2: cart, inventory, checkout/payment, orders/shipping, notifications
- V3: trade completion/reputation, reviews, advanced trust/admin, AI,
  analytics

Do not implement V2 or V3 features while working on MVP slices. In particular,
business inventory, cart, checkout, payment, orders, shipping, and
notifications remain V2 even though the business seller portal exists in MVP.

## Product Invariants

1. Individual listings create **trades**, not platform orders.
2. Individual buyers and sellers arrange payment and delivery themselves.
3. The platform must not claim to verify or protect off-platform payment.
4. Beginning in V2, business listings use cart, inventory reservation,
   platform payment, order, and shipping.
5. Buyer, individual seller, business staff, and admin access share one user
   identity with scoped roles.
6. Business access always checks `businessId` membership and permission.
7. AI is optional and cannot bypass authorization, validation, or confirmation.
8. In the V3 trade-completion flow, individual sellers never receive a buyer's
   exact address.
9. Seller completion can target only the buyer already bound to the accepted
   trade created from a listing conversation; show only masked verified
   email/phone metadata.
10. An individual sale counts publicly only after seller initiation and
    authenticated buyer confirmation, and it increments exactly once.
11. Individual deal negotiation is free-text chat. Do not add structured offer,
    counteroffer, or offer-acceptance workflows unless the product scope is
    explicitly changed.

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

For new or changed functions/methods, add a concise purpose comment when the
function participates in a business flow, authorization decision, integration
adapter, state transition, or non-obvious technical rule. Keep comments useful:
explain what the function is responsible for or why the rule exists, not a
line-by-line restatement of obvious code. Trivial getters/setters, framework
boilerplate, and self-explanatory test setup do not need noise comments.

## Repository Direction

- Java 21 and Spring Boot remain the backend baseline.
- Angular is the MVP frontend framework.
- MySQL is the default transactional database for new MVP data.
- Redis will store V2 carts, rate limits, and other temporary state.
- Kafka will carry durable domain events when an approved slice needs them.
- OpenSearch is a derived listing-search index.
- S3-compatible object storage holds listing media.
- V3 OpenAI access occurs only through the isolated agent service.
- Stable technical code is shared through small Maven and Angular libraries,
  not a runtime common service.

Avoid adding PostgreSQL, MongoDB, RabbitMQ, Kubernetes, or another frontend
framework to new MVP paths without an approved architecture decision.
Existing technology may remain while a documented migration is in progress.

## Shared Library Rules

Approved backend shared modules:

- `common-core`
- `common-web`
- `common-testing`

Allowed shared content includes money/value types, pagination primitives, API
errors, correlation handling, and testing utilities.

`common-security` and `common-events` are deferred until an approved feature
requires them. Do not create empty speculative shared modules.

Do not put JPA entities, repositories, migrations, controllers, service
business logic, or domain aggregates in shared modules. Do not make every
service depend on every common module. Environment variables and secrets do
not belong in shared constants.

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
- Keep immutable history for trade state, order state, payments, moderation,
  and audit actions.
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
- Do not accept buyer email, phone, address, or replacement buyer ID in a
  seller completion request; derive the buyer from the trade.
- Store completion challenges hashed, single-use, expiring, and rate-limited.

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

- During Phase 1: build baseline, shared-module architecture rules,
  correlation/error plumbing, configuration validation, and CI behavior
- Later MVP: listing visibility, chat participant authorization, and basic
  moderation authorization
- V2: concurrent inventory reservation and payment recovery
- V3: trade-completion idempotency and AI tool authorization

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
