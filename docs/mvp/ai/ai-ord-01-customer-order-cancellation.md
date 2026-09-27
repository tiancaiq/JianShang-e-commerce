# AI-ORD-01 — Customer Order Cancellation Through Marketplace Agent

## Status

Implemented and accepted in the disposable demo stack behind an independent
default-off Agent feature flag. The 2026-09-08 acceptance record is
`docs/qa/ai-ord-01-exploratory-acceptance-2026-09-08.md`. Production provider
and operational rollout remain separate decisions.

## Goal

Allow an authenticated customer to ask Marketplace Agent V2 to cancel one of
their own eligible business orders. The Agent prepares a durable Level 3
confirmation and, after an exact single-use confirmation, submits the existing
customer cancellation command to Order Service.

Order Service owns the complete cancellation and compensation sequence. Agent
Service does not cancel an order directly, decide refund eligibility, issue a
refund, release inventory, or use Admin order APIs.

## Existing architecture reused

AI-ORD-01 reuses these existing customer contracts:

- `GET /api/v1/orders` for newest-first owned order references;
- `GET /api/v1/orders/{orderId}` for the owned immutable order snapshot,
  version, buyer-safe cancellation eligibility, request status, inventory
  status, and local-demo refund status;
- `POST /api/v1/orders/{orderId}/cancellation-requests` with `If-Match` and
  `Idempotency-Key` for the customer cancellation request.

The Order Service command resolves the buyer from the delegated authenticated
actor, locks the owned order, validates the exact order version and immutable
cancellation policy snapshot, and writes the cancellation request, order/group
state, history, and outbox record transactionally. Its existing processing
worker owns auto-decision, Inventory restock, Payment Service refund, retry,
and final completion.

No new Order, Payment, Inventory, Gateway, or public API contract is introduced.

## Capabilities

The `CUSTOMER_ORDER_MUTATION` family contains:

- `preview_my_order_cancellation` — read-only preparation. It accepts only an
  order reference already grounded by an actor-owned order observation, reads
  the authoritative order detail, and creates a confirmation only when Order
  Service reports the order eligible.
- `cancel_my_order` — Level 3 application-only execution. Direct model
  proposals are rejected with `CONFIRMATION_REQUIRED`. The orchestrator invokes
  it only after the durable confirmation is atomically consumed.

Neither tool accepts a buyer, customer, actor, refund, payment, inventory,
provider, business, or Admin identity.

## Intent and reference resolution

Informational questions do not create a confirmation. Explicit commands may
use an exact order, latest/recent order, ordinal, named purchase-time item, or
a clear follow-up such as “cancel it.” The model must first resolve those terms
from `list_my_orders` or `get_my_order`; the proposal policy rejects an
ungrounded order identifier.

If multiple orders remain plausible, the Agent asks one focused question and
does not prepare or execute a cancellation. Partial item/group cancellation is
not supported because the existing customer command is whole-order only.

## Preview and confirmation binding

A successful preview creates one AI-CONF-01 record with:

- actor user ID and Marketplace Agent conversation/session ID;
- originating invocation ID;
- workflow `CUSTOMER_ORDER_CANCELLATION`;
- capability `cancel_my_order`, version
  `customer-order-cancellation-v1`, and action `CANCEL_ORDER`;
- normalized arguments containing only order ID, order version, order
  fingerprint, total, and currency;
- one `ORDER` target with exact ID, version token, and snapshot fingerprint;
- one order-total financial fact for immutable binding, not as a refund
  promise;
- Risk Level 3 expiry and the stable `agent-action-{confirmationId}` action key.

The public pending interaction excludes confirmation identity, arguments,
fingerprints, versions, expiry internals, and action keys. Its summary explains
that the request applies to the whole order and that Order Service owns any
inventory release and local demo refund.

## Revalidation and execution

Before the confirmation can be consumed, Agent Service re-reads the owned
order and compares:

- actor ownership, enforced again by Order Service;
- feature/capability availability;
- exact order ID and version;
- normalized snapshot fingerprint;
- total and currency;
- current Order-owned cancellation eligibility; and
- absence of an existing cancellation request.

Any mismatch makes the old confirmation unusable. A changed order version,
fulfillment start, closed policy window, existing request, authentication
failure, capability disablement, expiry, unsafe interruption, or replacement
request results in zero cancellation command executions.

After one atomic `CONSUMED` claim, the application sends the existing bodyless
customer command with the bound `If-Match` version and stored action key as the
idempotency key. A lost response is retried only with that same key. If both
responses are unavailable, a fresh owned order read reconciles an already
created cancellation request; otherwise the result remains unknown and the
Agent does not claim success or retry with a new key.

## Customer-visible outcomes

The Agent distinguishes:

- cancellation request accepted;
- cancellation/compensation complete;
- fulfillment already started;
- cancellation window closed or policy disallowed;
- request already exists;
- stale order version;
- authentication or dependency failure; and
- unknown outcome requiring an order check before another attempt.

Acceptance of a request is not presented as proof that compensation has
completed. Refund language always identifies the existing local demo flow and
never implies real money movement.

## Safety and privacy

- Cross-user access and mutation remain AI-POL-00 unsafe requests.
- Admin cancellation, forced refunds, payment/provider manipulation, direct
  inventory release, seller operations, and internal service invocation remain
  unavailable.
- An AI-POL-00 Level 5 turn invalidates pending confirmation authority.
- Refresh/reconnect restores the same durable pending interaction and never
  prepares or executes another action automatically.
- Duplicate and concurrent confirmations can produce at most one database
  consume claim; Order Service idempotency is the second exactly-once boundary.
- Purchase-time order history is preserved. Cancellation appends state and
  compensation evidence rather than rewriting historical item snapshots.
- Agent observations exclude addresses, phone numbers, tracking numbers,
  business/store identifiers, policy versions, payment/provider references,
  refund IDs, and cancellation request IDs.
- Marketplace and order text is untrusted data and cannot expand the runtime
  tool registry or bypass policy.

## Feature flag

`AGENT_MARKETPLACE_V2_ORDER_MUTATIONS_ENABLED=false` is the independent
default. Enabling it also requires Marketplace Agent V2, persistence, commerce
reads, and a valid `ORDER_SERVICE_URL`. Disabling it removes both tools from the
provider registry and rejects execution before I/O. A confirmation prepared
before a restart with the flag disabled is invalidated and cannot execute.

The existing Order Service cancellation-request and cancellation-processing
flags remain separately owned and are not enabled by AI-ORD-01.

## Persistence and audit

Agent migration V23 extends the existing tool-call check constraint only for
`preview_my_order_cancellation` and `cancel_my_order`. Execution audit stores
hashed confirmation, order, and action references; it does not store bearer
tokens, request bodies, or customer PII. AI-CONF transition rows retain the
full authoritative lifecycle: `PENDING`, `CONFIRMED`, `CONSUMED`, `CANCELLED`,
`EXPIRED`, or `INVALIDATED`.

## Acceptance gate

Release requires automated and live evidence for:

- explicit versus informational intent;
- latest, ordinal, named-item, pronoun, and ambiguous references;
- actor and conversation isolation;
- exact order/action/version/fingerprint binding;
- expiry, decline, replay, duplicate, and concurrent confirmation;
- stale fulfillment/policy/version state;
- feature disablement and unsafe interruption after preparation;
- bodyless versioned Order Service command and stable idempotency;
- timeout reconciliation;
- pending and failed compensation without false completion claims;
- existing refined-search confirmation, AI-COM-01 cart, and AI-CHK-01 checkout
  regressions; and
- environment and data restoration.

No live release verdict is claimed by implementation tests alone.
