# ADM-ENF-00 Shared Enforcement Foundation

Status: complete for the shared foundation and the user, business, and listing
runtime integrations.

## Boundary and ownership

`ADM-ENF-00` defines auditable, reversible enforcement records used by the
completed user, business, and listing admin verticals. The foundation itself
did not expose a production mutation controller or admin action UI; the
target-specific verticals now expose those workflows only where the matching
runtime boundary is implemented. Enforcement never disables Keycloak, enables
reserved login/messaging/payout behavior, creates reports or appeals, or
cascades to another target.

Auth Service owns `USER` and `BUSINESS` enforcement. Product Service owns
`LISTING` enforcement. Each service has its own tables, repository, target
version check, application service, evaluator, timeline mapper, permission
mapping, and tests. No service reads another service's schema. `common-core`
remains limited to technical primitives, so enforcement domain types are
service-local equivalents pinned by this canonical contract and focused tests.

## Vocabulary

Actions have the severity order `RESTRICT < SUSPEND < BAN`. An action stores
only explicit scopes:

- User: `USER_LOGIN`, `USER_BUYING`, `USER_SELLING`, `USER_MESSAGING`.
- Business: `BUSINESS_LISTING_CREATION`, `BUSINESS_LISTING_PUBLICATION`,
  `BUSINESS_NEW_SALES`, `BUSINESS_PAYOUTS`.
- Listing: `LISTING_PUBLIC_VISIBILITY`, `LISTING_PURCHASABILITY`.

`USER_BUYING` and `USER_SELLING` are operational through `ADM-USER-01/02`;
`USER_LOGIN` and `USER_MESSAGING` remain reserved. `BUSINESS_LISTING_CREATION`,
`BUSINESS_LISTING_PUBLICATION`, and `BUSINESS_NEW_SALES` are operational through
`ADM-BUS-04/05`; `BUSINESS_PAYOUTS` remains reserved. Both listing scopes are
operational through `ADM-LIST-06`. Product accepts `RESTRICT` and `SUSPEND` for
listing enforcement under `admin.listing.suspend`; listing `BAN` is rejected
because permanent administrative removal has its own lifecycle workflow.

Sources are `HUMAN_ADMIN`, `SYSTEM`, and reserved `AI_AGENT`. Current command
services always derive a human actor from the authenticated admin session.
`AI_AGENT` has no service account, role assignment, permission, or execution
path. `SYSTEM` is reserved for genuine server lifecycle work.

Lifecycle is derived as `ACTIVE`, `EXPIRED`, or `REVOKED`. Active means the
effective time has arrived, expiration has not arrived, and no revocation is
recorded. Material future activation is rejected; sub-five-second clock skew is
normalized to server time. Expiration is query-time state and has no scheduler.

## Persistence and lifecycle

Both owning schemas contain service-local equivalents of:

- `enforcement_actions`: immutable decision context plus an optimistic version
  and nullable revocation fields. It includes target/action, explicit times,
  reason code and human reason, optional case and parent IDs, target version,
  source, trusted actor ID/display, correlation/request IDs, idempotency key,
  SHA-256 command fingerprint, and allow-listed JSON metadata.
- `enforcement_action_scopes`: normalized scopes keyed by action and scope.
- `enforcement_events`: append-only `CREATED` and `REVOKED` lifecycle events.
- `enforcement_command_idempotency`: durable slots scoped by command type and
  key, linked to the committed result.

Rows are never hard-deleted. Foreign keys use `ON DELETE RESTRICT`. The
Product-owned action target references the local listing. Auth validates and
locks either its JPA user aggregate or local business row; a polymorphic
User/Business target intentionally has no generic foreign key. The optional
parent action field reserves explicit future cascade lineage, but ADM-ENF-00
creates no child action or cascade.

## Commands, concurrency, and idempotency

Internal create commands contain target, action, scopes, reason, optional case,
effective/expiration times, expected target version, idempotency key, safe
metadata, and `dryRun`. Revoke commands contain the action ID, expected action
version, reason, idempotency key, metadata, and `dryRun`. Clients cannot supply
actor, source, correlation, or request identity.

Mutations require an idempotency key. Canonical payloads are fingerprinted.
The same key and fingerprint returns the original result; changed payload is a
conflict. Database primary/unique constraints and locked target/action rows
serialize concurrent commands. Validation failures and dry runs do not reserve
a key. Exact active duplicates with the same target, action, and scope set are
conflicts; overlapping actions with different scopes or severities are allowed.
No command silently retries a stale target or action version.

Dry runs perform permission, existence, version, compatibility, expiration,
duplicate, and revocation checks. They write no action, scope, event, or
idempotency row and do not resolve identity labels or invoke runtime-effect
services. Product consumes the already-required Auth Service admin-session
authorization boundary; it does not make a second actor-label call.

## Effective evaluation and revocation

Each service exposes a reusable evaluator. For every requested target scope it
selects the strongest currently active action and returns that action ID.
Weaker and differently scoped actions remain unchanged. Revoking a stronger
action increments its version, appends `REVOKED`, and reveals any weaker active
action. Reinstatement is therefore revocation, never a second `REINSTATE`
restriction.

## Authorization and audit

Auth creation maps `RESTRICT`, `SUSPEND`, and `BAN` to the corresponding
`admin.user.*` or `admin.business.*` permission. Auth revocation requires the
target's `reinstate` permission. Product listing creation requires
`admin.listing.suspend`; revocation requires `admin.listing.reinstate`.
`SUPER_ADMIN` receives these through the persisted ADM-SEC-01 role mapping.
Auditors can use later read adapters but cannot call mutation services without
the mutation permission.

Lifecycle events map at application level to the normalized admin timeline:
event/time/type, safe actor/source, target/action/case, previous/new state,
action/scopes, reason code/reason, correlation/request IDs, and safe metadata.
Denied, invalid, dry-run, stale, and rolled-back commands create no domain event.
No new timeline page or public read endpoint is part of this slice.

## Existing flows and deferred vertical slices

Existing listing moderation and `REMOVED_BY_ADMIN` remain separate and
unchanged. Listing suspension does not mean removal. Marketplace enforcement
does not disable a Keycloak identity. User actions do not affect businesses or
listings; business actions do not affect listings, orders, or payouts; listing
actions do not affect sellers.

User, business, and listing runtime integration is implemented without
cross-target cascades. The next admin milestone is `ADM-REP-00/01/02`; reports,
investigation cases, appeals, disputes, payout enforcement, and AI automation
remain deferred and are not enabled by this foundation.

## Business runtime activation (ADM-BUS-04/05)

Business enforcement is now runtime-effective for `BUSINESS_LISTING_CREATION`, `BUSINESS_LISTING_PUBLICATION`, and `BUSINESS_NEW_SALES`. `BUSINESS_PAYOUTS` remains reserved. See [admin-business-control.md](admin-business-control.md) for ownership, fail-closed behavior, no-cascade rules, and existing-order preservation.
## Listing target specialization (`ADM-LIST-06`)

Product Service is the authoritative owner for `targetType=LISTING`. Listing actions are limited to `RESTRICT` and `SUSPEND`; `BAN` is invalid. Listing scopes are `LISTING_PUBLIC_VISIBILITY` and `LISTING_PURCHASABILITY`, and `SUSPEND` always expands to both.

Effective listing policy is evaluated independently from user and business policy. Revoking a listing action cannot clear a user or business restriction. Revocation and expiry recalculate from remaining listing actions. Neither operation changes listing status, moderation status, content, inventory, seller/business state, or existing orders. `REMOVED_BY_ADMIN` remains the authoritative non-reversible removal state and cannot receive temporary listing enforcement.

Product MySQL is checked synchronously for public listing exposure. Order Service calls Product's bounded internal batch decision at reservation, payment-intent, and confirmation boundaries and fails closed on decision unavailability.
