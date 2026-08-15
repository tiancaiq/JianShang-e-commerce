# ADM-USER-01/02 User Administration and Runtime Enforcement

Status: implemented.

## Purpose and ownership

This slice is the first production workflow built on `ADM-ENF-00`. Auth
Service owns user discovery, privacy, action lifecycle, effective user
capabilities, and the internal decision API. Product Service and Order Service
consume capability decisions at their own authoritative mutation boundaries.
No service reads another service's database and no new service, Kafka flow, or
distributed policy engine is introduced.

User administration is search, inspection, marketplace restriction,
suspension, marketplace ban, and reinstatement. There is no generic user
approval. Seller activation and business approval remain separate workflows.

## Admin workflow

1. Open `/admin/users`. Search by user ID or display name, filter by effective
   enforcement state or operational scope, and use stable server pagination.
2. Open `/admin/users/{userId}`. Review separate account, authentication,
   individual-seller, business-membership, platform-admin, marketplace
   capability, active action, historical action, and audit sections.
3. Choose `RESTRICT`, `SUSPEND`, or `Marketplace ban`. Only operational scopes
   are selectable. Supply a reason code, explanation, and optional expiration;
   an indefinite suspension requires explicit acknowledgement.
4. Run the dry run. Review explicit scopes, overlapping actions, predicted
   effective restrictions, permanence, warnings, and target-version status.
   The dry run creates no action, event, idempotency row, or runtime block.
5. Confirm once. The UI retains one idempotency key for that confirmation
   attempt and refreshes detail and audit history on success. A stale `409`
   clears the preview and reloads current state without retrying the command.
6. To reinstate, select one active action, enter a reinstatement reason, preview
   the post-revocation restrictions, and confirm. The UI does not claim all
   access is restored when a weaker active action will remain.

## Privacy and authorization

`admin.user.read` gates search/detail. `admin.audit.read` independently gates
the timeline. Full email display and email search require
`admin.user.pii.read`; otherwise Auth returns only a masked value. Masking is
performed before serialization, not by CSS. Tokens, raw claims, credentials,
and unnecessary identity-provider identifiers are never returned.

Create permissions are action-specific:

| Action | Permission |
|---|---|
| Restrict | `admin.user.restrict` |
| Suspend | `admin.user.suspend` |
| Marketplace ban | `admin.user.ban` |
| Revoke/reinstate | `admin.user.reinstate` |

`USER_RESTRICTOR` is the least-privileged operator role for this workflow. It
has user read, audit read, restrict, and reinstate permissions, but cannot
suspend, ban, view full email, or manage roles. `SUPPORT_ADMIN` and `AUDITOR`
remain read-only.

The backend derives actor, source, user target, request identity, and
correlation identity from trusted context. Frontend capability checks improve
the workflow but are not an authorization boundary.

An administrator cannot enforce their own marketplace account. `SERVICE` and
`AUTOMATION` account types are protected explicitly; they are not inferred from
email. Only `SUPER_ADMIN` may enforce another platform administrator, and even
that changes only the selected marketplace scopes. It does not remove roles,
disable `/admin`, or disable Keycloak.

## Scope status and action semantics

| Scope | Status | Runtime meaning |
|---|---|---|
| `USER_BUYING` | Operational | Blocks new checkout commitment and payment-intent creation |
| `USER_SELLING` | Operational | Blocks seller activation, listing mutations, and a new individual-sale completion |
| `USER_LOGIN` | Reserved | Sign-in is unchanged and the scope is not selectable |
| `USER_MESSAGING` | Reserved | Messaging is unchanged and the scope is not selectable |

`RESTRICT` and `SUSPEND` store the selected operational scopes. A marketplace
ban is not account deletion: the server deterministically expands `BAN` to both
operational scopes and persists those explicit scopes. Revocation marks one
action revoked and appends history; it does not delete or rewrite the action.
The strongest active action wins independently for each scope, so revoking a
stronger action can reveal a weaker action.

## Runtime enforcement boundaries

Buying checks run before checkout inventory reservation and before a payment
intent is created. Exact checkout command replay is resolved before a fresh
buying decision. Cart viewing/mutation, public browsing, account access,
existing-order access, eligible cancellation/refund, and existing fulfillment
are unchanged.

Selling checks run before individual seller activation and before Product
listing create, edit, close, submit/resubmit, publish/activate, pause/relist,
and listing-media mutations. A business listing mutation checks the acting
member's user ID; another unrestricted authorized member remains unaffected.
Buyer confirmation of an individual trade checks the listing owner's current
selling capability before Product closes the listing. Reads and already-closed
completion replay remain available.

An explicit denial is `403 USER_CAPABILITY_RESTRICTED`. A malformed response,
bad service authentication, timeout, or decision-service outage is
`503 ENFORCEMENT_DECISION_UNAVAILABLE`; protected writes fail closed. Decision
responses contain no reason, actor, case, PII, or private metadata.

## Safe user status

`GET /api/v1/users/me/marketplace-capabilities` always resolves the
authenticated application user and returns buying/selling availability plus
only safe applicable restriction fields: scope, effective action,
effective/expiration time, and support reference. Login and messaging are
reported available because those scopes remain reserved. There is no appeal
workflow in this milestone.

## No-cascade and compatibility guarantees

A user action does not update business status, membership, listing status,
listing enforcement, Keycloak state, roles, carts, orders, payments, payouts,
or another user. A marketplace ban does not delete the user or listings and
does not cancel existing obligations. Reinstating a user does not reinstate a
business or listing. Existing business approval, listing moderation/removal,
admin authorization, and normalized timeline contracts remain separate.

The forward migration adds the explicit account type and stable user search
indexes. It does not rewrite existing enforcement history. Confirmed commands
retain `ADM-ENF-00` durable idempotency, command fingerprinting, target/action
optimistic checks, target/action locking, and atomic append-only events.

## Deferred work

Login and messaging enforcement, reports, investigations, appeals, support
notes, chat evidence, Keycloak account disablement, admin role management,
finance operations, payout enforcement, and AI automation remain deferred.
Business and listing enforcement are documented separately in
[admin-business-control.md](admin-business-control.md) and
[admin-listing-enforcement.md](admin-listing-enforcement.md). The next admin
milestone is `ADM-REP-00/01/02`.
