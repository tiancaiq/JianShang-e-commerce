# V2-COM-RC-01 Commerce Release Candidate Stabilization Gate

## Scope

This slice adds no customer-facing capability. It establishes a reproducible
gate for the already-approved bounded V2 commerce lifecycle: clean schema,
deterministic fixtures, real-service HTTP journeys, restart/replay recovery,
concurrency/idempotency, authorization, accounting, diagnostics, frontend
acceptance, and CI.

## Runtime reconciliation

| Concern | Prior drift risk | RC authority/detection |
| --- | --- | --- |
| Compose | Base/demo could recreate a marketplace-only service set | `docker-compose.demo.yml` plus mandatory `docker-compose.cart-runtime.yml` |
| Frontend | Production/default build could replace demo commerce routes | Image embeds `commerce-runtime.json`; preflight requires `demo-checkout` |
| Feature flags | Split across shell environment and container defaults | Overlay enables them and applies `V2_COMMERCE_RC_01` to every commerce container |
| Database | Base files referenced MySQL 8.3 while integration target is 8.4 | Overlay and Testcontainers gates use MySQL 8.4; preflight queries the live server |
| Redis | Runtime version/capability was not asserted | Overlay pins Redis 7.4 and preflight requires PING |
| Service URLs/routes | A healthy container could still lack commerce routing | Preflight checks live container environment and every bounded gateway capability |
| Fixtures | Buyer, seller, cart, and fulfillment setup was split | `prepare-commerce-demo.ps1` is the supported idempotent orchestrator |
| CI/browser | Could build or test a different frontend configuration | CI builds demo commerce and uses the same runtime marker/preflight |

## Executable evidence

- `tools/verify-commerce-clean-migrations.ps1`: independently starts every
  commerce service schema from empty MySQL 8.4 through current forward
  migrations.
- `tools/commerce_rc_acceptance.mjs`: actual identity, gateway, service, Redis,
  and MySQL boundaries for a two-business fulfillment/return and a fresh
  cancellation, including controlled service stops and restarts.
- `tools/verify-commerce-concurrency.ps1`: complete integration classes for
  cart, checkout, payment callback, order confirmation, acceptance,
  fulfillment, cancellation, returns, refunds, outboxes, and replay.
- `tools/check-commerce-invariants.ps1`: global state, stock, refund, restock,
  and notification-deduplication assertions.
- `tools/check-commerce-health.ps1`: aggregate pending/retrying/failed event
  counts, attempts, failure timestamps, and age without payload exposure.
- `scripts/verify-commerce-runtime.ps1`: actual runtime/profile/capability
  preflight rather than container-health-only verification.

## Authorization matrix

`ALLOW` is actor-owned access. `HIDE` is consistent 404/empty behavior for a
guessed or foreign identifier. `DENY` is authentication/role rejection.

| Action/resource | Guest | Buyer A | Buyer B | Seller A owner | Seller B owner | Unauthorized authenticated |
| --- | --- | --- | --- | --- | --- | --- |
| Cart / checkout / address book | DENY | ALLOW own | ALLOW own | own buyer context only | own buyer context only | own buyer context only |
| Buyer order / cancellation / return request | DENY | ALLOW own | HIDE foreign | HIDE foreign | HIDE foreign | HIDE foreign |
| Seller business order | DENY | DENY | DENY | ALLOW A, HIDE B | ALLOW B, HIDE A | DENY/HIDE |
| Fulfillment / return authorization or receipt | DENY | DENY | DENY | ALLOW A, HIDE B | ALLOW B, HIDE A | DENY/HIDE |
| Buyer notifications | DENY | ALLOW own | ALLOW own, no A | buyer context only | buyer context only | ALLOW own only |
| Seller notifications | DENY | DENY | DENY | ALLOW A, HIDE B | ALLOW B, HIDE A | DENY/HIDE |

Public DTOs must not expose full address internals beyond the approved order
view, memberships, provider events, outbox payloads, tokens, service secrets,
or mutable authoritative inventory/payment values. Refund amounts come only
from immutable order/business-group snapshots.

The active local Payment transport claims only `payment.succeeded` and
`payment.failed`. Local refund rows remain durable deferred audit events until
an approved refund-event consumer exists; the dispatcher no longer retries
them as invalid success/failure envelopes. Health output labels this count
separately instead of misreporting it as actionable delivery backlog.

## State and accounting invariants

The gate retains the approved state machines. Forward migration
`V14__enforce_return_terminal_state_consistency.sql` rejects received/completed
returns without a receipt disposition and rejects completed returns without
successful refund evidence. Runtime invariant queries additionally reject
cancelled-and-shipped groups, shipment without processing history, return
before delivery, negative/over-reserved stock, duplicate restoration/restock,
duplicate notification source events, and total successful refunds above the
successful payment.

For stable fixtures the acceptance equation is asserted from the database:

```text
initial on-hand - committed purchase + cancellation restoration
+ eligible return restock = final on-hand
```

`DO_NOT_RESTOCK` is separately constrained to create no sellable movement.

## Recovery contract

The orchestration deliberately stops Order after payment success, Notification
after order events, Inventory/Payment during cancellation compensation, and
Payment after return receipt. Durable events/workers must converge to one
order, one stock movement per purpose, one exact refund per scope, and one
notification per recipient/source event. Commerce state remains committed when
notification delivery is delayed.

## Decision

This gate is green only when the documented commands, CI workflow, full service
suites, and browser checklist all pass against the same revision. A selective
unit-test pass is not sufficient. Real payment, carrier, payout, partial-return,
review, promotion, and recommendation integration remains explicitly deferred.
