# Repository Instructions

## Scope and source of truth

These instructions apply repository-wide. More specific `AGENTS.md` files add
rules for their subtrees.

The approved MVP specification is:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`

These documents override older README, tutorial, and stale planning material.
Before implementing a slice, read its roadmap entry and related approved
documents.

## Delivery boundary

Implement one small approved roadmap slice at a time. Do not combine slices
unless they are inseparable and the reason is documented.

The product surfaces are:

- Public marketplace: guest browsing and search; signed-in profile, individual
  selling, and chat.
- Business seller portal: approved merchant onboarding, store profile, and
  basic business listing management.
- Admin portal: business approval and listing moderation.

Individual seller profiles and listings belong to the marketplace account
experience, not the business seller portal.

Release placement remains:

- MVP: foundation, accounts, seller profiles, listings/media, guest discovery,
  search/storefront, basic chat, and basic business/listing moderation.
- V2: business cart, inventory, checkout/payment, orders/shipping, and
  notifications.
- V3: trade completion/reputation, reviews, advanced trust/admin, AI, and
  analytics.

Do not introduce later-release behavior into an unrelated MVP slice. This
schedule is not a blanket rejection of agent work: explicitly requested and
approved marketplace-agent or agent-service slices may be implemented within
their documented contract and rollout boundaries.

## Product invariants

1. Individual listings create trades, not platform orders; buyers and sellers
   arrange payment and delivery themselves.
2. Never claim the platform verifies or protects off-platform payment.
3. Beginning in V2, business listings use the distinct cart, inventory,
   payment, order, and shipping flow. Never merge trade and order state
   machines.
4. Buyer, individual seller, business staff, and admin access share one user
   identity with scoped roles. Business access always checks `businessId`
   membership and permission.
5. AI is optional and cannot bypass authorization, validation, or required
   human confirmation.
6. Individual deal negotiation is free-text chat; do not add structured offers
   without an approved scope change.
7. In the V3 completion flow, the seller may target only the buyer already
   bound to the accepted trade. Never expose the buyer's exact address; show
   only masked verified contact metadata.
8. A public individual-sale count changes only after seller initiation and
   authenticated buyer confirmation, exactly once.

## Architecture boundaries

- Java 21 and Spring Boot are the backend baseline; Angular is the frontend.
- MySQL is authoritative transactional storage. Redis is temporary state,
  OpenSearch is derived search data, and S3-compatible storage holds media.
- Use Kafka only for approved durable asynchronous flows, with a transactional
  outbox and event-ID deduplication.
- Each service owns its schema. Never query or update another service's
  database; use synchronous APIs for immediate checks and events for approved
  asynchronous projections.
- Do not add PostgreSQL, MongoDB, RabbitMQ, Kubernetes, another frontend
  framework, or a new microservice without an approved architecture decision.
- Prefer adapters around external payment, shipping, email, storage, and AI
  providers.

Approved shared backend modules are `common-core`, `common-web`,
`common-storage`, and `common-testing`. Share only stable technical primitives;
do not place entities, repositories, migrations, controllers, domain logic, or
aggregates in shared modules. Keep `common-storage` technical, avoid universal
module dependencies, and never put secrets in shared constants. Do not create
speculative `common-security` or `common-events` modules.

## Workflow

Before changing code:

1. Identify the requirement or roadmap slice and its dependencies.
2. Read the relevant API, data-ownership, and service-local instructions.
3. Inspect the implementation and uncommitted changes.
4. Surface a contract ambiguity before inventing behavior.

Implement the smallest end-to-end path required by the slice. Update contracts
only when the approved behavior changes, add proportionate tests, and add safe
structured logs or metrics for important failure paths.

For non-obvious business flows, authorization decisions, adapters, or state
transitions, give new or changed functions a concise purpose comment. Avoid
comments on boilerplate and self-explanatory code.

## Change safety

- Preserve unrelated working-tree changes.
- Do not edit `.env`, `.env.*`, or other local environment files during
  cleanup, stabilization, refactoring, or documentation work unless explicitly
  requested.
- Never rewrite a migration that may have run; add a forward migration.
- Avoid broad refactors in a small slice and never silently change an approved
  product invariant.
- Feature-flag incomplete workflows so users cannot enter dead ends.
- Do not store or log secrets, tokens, payment details, raw card data, external
  bank credentials, or unnecessary PII. Treat uploaded media and chat as
  untrusted input.

## Completion report

Report the requirement or roadmap IDs, files and contracts changed, migrations
added, tests run and results, and known limitations or deferred dependencies.
