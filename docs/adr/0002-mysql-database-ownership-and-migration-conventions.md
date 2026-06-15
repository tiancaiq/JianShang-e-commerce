# ADR-0002: MySQL Ownership and Migration Conventions

- Status: Accepted
- Date: 2026-06-15
- Roadmap: P1-05
- Related architecture: ADR-0001, `docs/mvp/database.md`

## Scope

This record defines database ownership, MySQL, Flyway, timestamp, identifier,
and money conventions for future MVP work.

It creates no feature tables and does not change existing application logic or
legacy migrations.

## Decision Summary

- MySQL 8 is the default transactional database for application-owned data.
- Every deployable service owns one logical schema and its Flyway history.
- Services never read or write another service's schema.
- New opaque business identifiers are ULIDs stored as `CHAR(26)`.
- Absolute timestamps are UTC `DATETIME(6)` values mapped to Java `Instant`.
- Money is `DECIMAL(19,4)` plus a three-character ISO 4217 currency code.
- Existing migrations are immutable. Corrections use new forward migrations.

## Database Ownership Map

| Domain | Deployable owner | Logical schema | Release | Authoritative storage |
|---|---|---|---|---|
| Credentials, login sessions, password recovery, email verification, MFA | Keycloak | `keycloak` | MVP | Keycloak-managed MySQL schema |
| Application user identity mapping, profile, account status, platform roles | `auth-service` | `identity` | MVP | MySQL |
| Individual seller profile | Marketplace module in evolved `product-service` | `marketplace` | MVP | MySQL |
| Business applications, businesses, memberships, invitations, stores, policies | Marketplace module in evolved `product-service` | `marketplace` | MVP | MySQL |
| Categories, listings, listing attributes, media metadata, listing status history | Marketplace module in evolved `product-service` | `marketplace` | MVP | MySQL |
| Media file bytes and derivatives | Marketplace module | None | MVP | S3-compatible object storage |
| Listing search projection | Search projector | None | MVP | OpenSearch, rebuilt from marketplace data/events |
| Basic business and listing moderation | Moderation module deployed with marketplace administration | `marketplace` | MVP | MySQL |
| Conversations, participants, and messages | Future `trade-service` | `trade` | MVP | MySQL |
| Individual trade completion and seller reputation | Future `trade-service` | `trade` | V3 | MySQL |
| Cart | Commerce flow | None | V2 | Redis, revalidated before checkout |
| Inventory items, movements, and reservations | `inventory-service` | `inventory` | V2 | MySQL |
| Checkout, orders, fulfillment groups, order snapshots, shipping | `order-service` | `orders` | V2 | MySQL |
| Payments, provider events, refunds, payout projections | `payment-service` | `payments` | V2 | MySQL |
| Notifications, preferences, and delivery attempts | `notification-service` | `notifications` | V2 | MySQL |
| Reviews, reports, suspensions, support, and advanced trust operations | Trust module initially deployed with marketplace administration | `marketplace` | V3 | MySQL |
| Agent sessions, tool audit, and cost records | Future `agent-service` | `agent` | V3 | MySQL |
| Gateway BFF sessions and rate limits | `api-gateway` | None | MVP | Redis |
| Durable domain events | Producing service | Owning service schema | As needed | Transactional outbox in MySQL, then Kafka |

The `api-gateway`, search projector, and shared code modules do not own
application MySQL schemas.

Keycloak exclusively manages the `keycloak` schema. Application migrations,
queries, reports, and foreign keys must not reference Keycloak tables.

## Schema Ownership Rules

1. A service database user receives DDL/DML access only to its own schema.
2. A service may not query, join, update, or add foreign keys to another
   service's schema.
3. Cross-service validation uses a synchronous API when the caller needs an
   immediate answer.
4. Cross-service projections and notifications use versioned Kafka events.
5. Event producers write an outbox row in the same transaction as their
   aggregate change.
6. Consumers deduplicate by event ID in their own schema.
7. Redis, OpenSearch, Kafka, and S3 are not relational schema owners.
8. Local development may use one MySQL server, but it still uses separate
   schemas and service credentials.
9. A module deployed inside a service may use that service's schema. It does
   not receive a separate schema merely because it is a code module.
10. Splitting a module into a new service requires an architecture decision,
    data migration plan, and a period where only one owner accepts writes.

No shared database schema is permitted for `common-*` libraries.

## Identity Ownership After ADR-0001

Keycloak owns credential and authentication lifecycle data. New application
tables must not store:

- Password hashes
- Keycloak access, ID, or refresh tokens
- MFA secrets
- Password-reset tokens
- Keycloak login sessions

The `identity` schema stores an internal user ID and a unique immutable
Keycloak subject (`sub`) mapping. Email may be projected for application
communication and search but is not the identity key.

The legacy `auth-service` PostgreSQL migration and custom session/JWT model are
not the target design. They remain unchanged until a separately approved
migration slice replaces them.

## MySQL Conventions

### Engine and Encoding

- Supported baseline: MySQL 8.
- All application tables use `ENGINE=InnoDB`.
- Database and table character set is `utf8mb4`.
- Default collation is `utf8mb4_0900_ai_ci`.
- Case-sensitive machine identifiers, hashes, tokens, object keys, and
  idempotency keys use an explicit binary/ASCII collation as appropriate.
- Production SQL mode includes strict mode. Truncation and invalid dates must
  fail instead of being silently converted.
- Database, JDBC, service, and container time zones are UTC.

### Naming

- Schemas, tables, columns, indexes, and constraints use `lower_snake_case`.
- Table names are plural.
- Primary keys are named `id`.
- Foreign-key columns use `<entity>_id`.
- Foreign keys and unique constraints receive explicit stable names.
- Index names use `idx_<table>_<purpose>`.
- Unique constraint names use `uk_<table>_<purpose>`.
- Foreign-key names use `fk_<child>_<parent>`.
- Check constraint names use `chk_<table>_<rule>`.

Names must remain within MySQL's identifier limit. Migrations must not depend
on generated constraint names.

### Identifiers

New application aggregates use ULIDs:

```text
CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
```

Rules:

- Generate ULIDs in application code before persistence.
- Use uppercase canonical encoding.
- Treat IDs as opaque values in APIs and logs.
- Do not expose auto-increment database IDs for new MVP resources.
- Foreign-key columns use the same type and collation as the referenced ID.
- Join/ledger tables may use a composite primary key when the relationship is
  naturally unique and does not need its own public identity.

Keycloak `sub` is external data and is stored separately as `VARCHAR(64)` with
binary comparison and a unique constraint. Provider identifiers remain
strings and are not converted into ULIDs.

Existing `BIGINT` IDs may remain while legacy modules are migrated. Do not
rewrite a migration that has already created them. Translation to the target
identifier model requires an explicit migration plan.

### General Column Rules

- Required values are `NOT NULL`.
- Boolean values use `BOOLEAN`/`TINYINT(1)` consistently through JPA.
- Status values use stable `VARCHAR` strings, not MySQL `ENUM`.
- JSON is permitted for configuration, provider payload snapshots, or event
  payloads, not as a substitute for modeled transactional relationships.
- Mutable aggregates include an integer `version` for optimistic locking.
- Store encrypted/protected PII only where the owning domain requires it.
- Never store raw card data, passwords, bearer tokens, or external bank
  credentials.

### Index and Constraint Rules

- Add indexes for documented API queries, foreign-key access, worker queues,
  expiry cleanup, and idempotency lookup.
- Composite index column order follows equality predicates, then range/sort
  predicates.
- Avoid duplicate indexes that are prefixes of an existing index.
- Use database constraints for invariants that can be expressed locally.
- Foreign keys are allowed only inside one service-owned schema.
- Do not add speculative indexes without a query or integrity requirement.

## Timestamp Conventions

Absolute instants are stored as:

```sql
DATETIME(6)
```

They map to `java.time.Instant`. `LocalDateTime` must not represent an absolute
database timestamp.

Rules:

- All absolute times are UTC.
- APIs and events use ISO 8601 UTC with a `Z` suffix.
- JDBC connections set the connection/session time zone to UTC.
- Hibernate uses `hibernate.jdbc.time_zone=UTC`.
- Services use an injected clock for testable current-time decisions.
- `created_at` is immutable after insertion.
- `updated_at` changes only when persisted business state changes.
- Expiry, publication, confirmation, cancellation, and deletion times use
  explicit nullable columns.
- Date-only business values use `DATE`.
- A user-entered local date/time must include or reference an IANA time zone
  before conversion to an `Instant`.
- Do not store Unix epoch numbers for ordinary domain timestamps.
- Do not use zero dates such as `0000-00-00`.

Every mutable aggregate normally contains:

```sql
version     BIGINT NOT NULL DEFAULT 0,
created_at  DATETIME(6) NOT NULL,
updated_at  DATETIME(6) NOT NULL
```

Application code owns `updated_at` so changes remain explicit and testable.
Database defaults may initialize timestamps, but migrations and bulk updates
must not rely on implicit `ON UPDATE` behavior.

## Money Representation

Every monetary value is represented by both:

```sql
amount    DECIMAL(19,4)
currency  CHAR(3) CHARACTER SET ascii COLLATE ascii_bin
```

Domain-specific column names include their meaning, such as
`unit_price_amount`, `subtotal_amount`, or `refund_amount`.

Rules:

- Java uses `BigDecimal`, never `double` or `float`.
- Currency is an uppercase ISO 4217 code.
- Amount and currency are both required when a monetary value exists.
- Amount and currency are validated together when the value is nullable.
- Prices, totals, fees, taxes, discounts, refunds, and snapshots are persisted
  explicitly; totals are never trusted from a client.
- Calculations may use greater intermediate precision, but persistence uses a
  scale of four.
- Rounding is explicit at a defined business boundary and defaults to
  `RoundingMode.HALF_EVEN` unless a payment/tax provider contract requires a
  documented alternative.
- Currency-specific payable minor-unit validation occurs before calling a
  payment provider.
- Non-negative constraints apply to prices and totals. Ledger deltas and
  adjustments may be signed when their domain explicitly permits it.
- An aggregate or checkout cannot silently combine currencies.

Do not store money as integer cents because currencies do not all use two
minor units and marketplace calculations may require sub-cent precision.

## Flyway Conventions

### Ownership and Location

Each service keeps migrations in:

```text
<service>/src/main/resources/db/migration
```

Each service has its own `flyway_schema_history` table in its owned schema.
Flyway runs with that schema as both the default and migration target.

Hibernate schema generation is prohibited:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

`none` is temporarily acceptable for a legacy module during migration, but
`create`, `create-drop`, and `update` are never allowed outside disposable
tests. Production DDL is applied only by Flyway.

### File Naming

Existing migrations such as `V1__init.sql` remain unchanged.

New migrations use a UTC timestamp version:

```text
VyyyyMMddHHmm__lower_snake_case_description.sql
```

Example:

```text
V202606151430__add_user_subject_mapping.sql
```

The author must check the service migration directory for a version collision
before committing. Descriptions identify one small roadmap or requirement
slice.

Repeatable migrations (`R__`) are reserved for replaceable database objects
such as views and are not used for tables, reference data, or business state.

### Migration Safety

1. Never edit, rename, reorder, or delete a migration that may have run.
2. Correct mistakes with a new forward migration.
3. Keep each migration small and owned by one service.
4. Separate schema expansion, application rollout, backfill, enforcement, and
   destructive cleanup when compatibility requires it.
5. A migration must remain compatible with the application version currently
   running during a rolling deployment.
6. Add nullable columns or safe defaults before making application code depend
   on them.
7. Backfill large datasets in bounded, restartable steps rather than one
   unbounded transaction.
8. Add `NOT NULL`, unique constraints, or foreign keys only after existing
   data has been validated.
9. Renames and type changes use expand-and-contract instead of an immediate
   destructive alteration.
10. Dropping columns, tables, indexes, or accepted status values requires a
    later cleanup migration after all readers and writers have moved.
11. Seeded reference values use versioned migrations with stable natural keys.
12. Migrations must not call another service or depend on another service's
    schema being present.
13. Avoid database-specific stored business logic, triggers, and scheduled
    events unless approved by an architecture decision.
14. Migration SQL must be deterministic and must not use local machine time.

For potentially blocking MySQL alterations, review table size, lock behavior,
algorithm support, and production rollout strategy before approval.

### Validation

Every service with a schema must verify:

- `flyway validate` succeeds against its supported MySQL version.
- A clean database can migrate from zero to the current version.
- An existing previous-version database can migrate forward.
- JPA validation succeeds after migration.
- No pending or checksum-mismatched migration exists.

Integration tests should use MySQL Testcontainers rather than H2 when testing
DDL, indexes, constraints, locking, or SQL behavior.

## Legacy Repository Impact

Current repository storage is mixed:

- `auth-service` uses PostgreSQL and a `V1__init_users.sql` migration.
- `product-service` uses MongoDB.
- Order, inventory, and payment modules already use MySQL and Flyway.

P1-05 does not migrate or delete any of these paths. Future feature slices:

1. Add new MySQL schemas and forward migrations under their owning service.
2. Introduce dual-read/backfill only through an approved migration plan.
3. Move one authoritative write path at a time.
4. Remove PostgreSQL or MongoDB dependencies only after data verification and
   rollback criteria are satisfied.

No new MVP table may be added to the legacy PostgreSQL or MongoDB paths.

## Consequences

Positive:

- One application relational engine and consistent operational practices
- Explicit ownership and tenant/security boundaries
- Portable, precise timestamps and monetary values
- Forward-safe migrations suitable for rolling deployment
- Clear transition away from tutorial-era mixed storage

Costs:

- Existing PostgreSQL and MongoDB data require deliberate migration
- Cross-service reporting needs APIs, events, or a separate read model
- ULID and money value types must be added to shared code in later approved
  setup slices
- Some schema changes require multi-release expand-and-contract deployment

## Completion Boundary

P1-05 is documentation-only. It does not:

- Create feature tables
- Add or alter Flyway migrations
- Change Hibernate configuration
- Add MySQL dependencies
- Move existing data
- Implement repositories, services, APIs, or business rules
