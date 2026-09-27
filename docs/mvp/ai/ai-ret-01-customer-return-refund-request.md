# AI-RET-01 — Customer Return and Refund-Request Workflow

## Status

Implemented behind an independent default-off Agent feature flag. Production
or customer-cohort activation requires a separate acceptance decision.

## Goal

Allow an authenticated customer to inspect return eligibility and existing
return/refund progress, then submit one exact return request for an eligible
business order group after durable AI-CONF-01 confirmation.

The Agent submits a customer request only. It cannot authorize a return,
resolve a dispute, choose or issue a refund, change payment/refund state, or
invoke seller or Admin finance operations.

## Existing architecture reused

AI-RET-01 adapts the existing Order Service buyer contracts:

- `GET /api/v1/orders` and `GET /api/v1/orders/{orderId}` resolve only the
  authenticated buyer's newest-first orders and purchase-time item snapshots;
- `GET /api/v1/orders/{orderId}/groups/{businessOrderId}/return` supplies the
  authoritative eligibility or existing return/refund projection; and
- `POST /api/v1/orders/{orderId}/groups/{businessOrderId}/returns`, with
  `If-Match` and `Idempotency-Key`, creates the existing customer request.

No AI-specific return engine or Order/Payment API is added. Order Service owns
the return state machine, seller authorization and receipt, inventory handling,
and later local-demo refund processing through Payment Service.

## Domain boundary

The existing V2 contract supports one return for an entire delivered business
order group within its policy window. It does not support item-line quantities,
selected items within a multi-item group, exchanges, customer approval, or
direct refund execution.

A customer-visible item name may resolve its containing group. The prepared
confirmation always lists the full affected group so the Agent cannot present a
whole-group request as a partial-item return.

If the customer explicitly asks to return only a selected item and its store
group contains other item lines, preparation is rejected with a clear
whole-group limitation. The request may proceed only when the selected item is
already the entire store group or the customer explicitly changes the request
to the whole group.

Supported request reasons remain the Order-owned values:

- `NO_LONGER_NEEDED`
- `NOT_AS_EXPECTED`
- `DAMAGED`
- `WRONG_ITEM`
- `OTHER`

An optional customer comment is trimmed and bounded to 500 characters. The
Agent accepts it only when the normalized text is present in the current
customer turn, so it cannot invent evidence, quantities, refund amounts, or
outcomes.

## Capabilities

The independently gated `CUSTOMER_RETURN_REQUEST` family contains:

- `get_my_return` — reads current eligibility or an existing return/refund
  projection for one owned order group;
- `prepare_my_return_request` — performs an authoritative eligibility read and
  prepares one exact whole-group Level 3 confirmation; and
- `submit_my_return_request` — application-only confirmed execution of the
  existing buyer request command.

Direct model proposals for `submit_my_return_request` are rejected with
`CONFIRMATION_REQUIRED`. No `issue_refund`, `approve_refund`,
`set_refund_status`, `resolve_dispute`, seller-return, or Admin finance tool is
registered.

No tool accepts an actor, buyer, user, account, business, refund, payment,
provider, or desired outcome. Authenticated actor identity is supplied outside
model control and Order Service independently enforces buyer ownership.

## Resolution and safe observations

Order and ordinal references must first come from `list_my_orders`. Named items
must come from `get_my_order` purchase-time references for the same owned order.
The proposal policy rejects invented or cross-order identifiers.

When an order has several business groups and no single existing return or
named purchased item identifies one, the Agent asks one focused group/item
question and creates no confirmation.

The model-visible return projection includes only customer-safe fields: store
name, affected purchase-time items, eligibility, bounded reason/comment,
request/return status, refund status and completed amount when authoritative,
window and lifecycle dates, and timeline. Return IDs, group/business/store IDs,
refund IDs, policy versions, tracking references, addresses, phone numbers,
provider references, and internal action data remain hidden.

## Confirmation binding and execution

Preparation creates one Risk Level 3 AI-CONF-01 record containing:

- actor, session/conversation, and originating invocation identity;
- workflow `CUSTOMER_RETURN_REQUEST`;
- capability `submit_my_return_request`, version
  `customer-return-request-v1`, and action `SUBMIT_RETURN_REQUEST`;
- exact Order-owned order-group ID and version;
- a normalized group fingerprint covering status, total/currency,
  purchase-time item IDs, quantities/prices, and delivery state;
- exact reason and optional customer comment; and
- the stable `agent-action-{confirmationId}` identity and bounded expiry.

No refund financial fact is bound or promised because customer submission does
not authorize compensation.

Before an atomic consume, Agent Service re-reads the owned order and return
projection and verifies the same group, version, fingerprint, eligibility, and
absence of an existing return. Feature disablement, authorization failure,
expiry, replacement, unsafe interruption, delivery/cancellation/window changes,
or any version/fingerprint mismatch causes zero submissions.

After one `CONSUMED` claim, the adapter calls only the buyer return-request API
with the bound version, reason/comment, and stored action key. A lost response
is retried only with that same idempotency key. If the response remains
uncertain, the Agent re-reads the return and reports success only when an
authoritative existing request is found.

## Customer-visible outcomes

Responses distinguish current eligibility, not-yet-delivered, cancelled group,
expired window, existing request, ambiguous group/item, stale version,
submission accepted, current return state, current refund state, dependency
failure, and unknown outcome.

`RETURN_REQUESTED` is described as a submitted request, never an approved
return. Refund progress is read-only. A completed local-demo refund is labeled
as demo compensation and never described as real-money protection.

## Rollout, persistence, and audit

`AGENT_MARKETPLACE_V2_RETURN_REQUESTS_ENABLED=false` is the independent
default. It requires Marketplace Agent V2, persistence, commerce reads, and a
valid `ORDER_SERVICE_URL`. Disabling it removes all three tools and makes a
previous confirmation fail policy revalidation before I/O.

Agent migration V24 extends the tool-call audit allowlist for the three narrow
capabilities. Audit data hashes confirmation, order-group, and action identity;
it does not persist access tokens, raw comments, or private return/refund IDs.

The existing confirmation lifecycle remains `PENDING`, `CONFIRMED`,
`CONSUMED`, `CANCELLED`, `EXPIRED`, or `INVALIDATED`, with actor/conversation
isolation and atomic single-use consumption.

## Acceptance gate

Release acceptance must cover owned latest/ordinal/named-item resolution,
multi-group ambiguity, whole-group disclosure, all supported reasons, optional
comment bounds, eligibility states, existing return and refund statuses,
decline/expiry/replay/concurrency, stale group/version and feature changes,
cross-user/cross-conversation attempts, unsafe interruption, lost-response
reconciliation, exact-once execution, and AI-COM/CONF/CHK/ORD regressions.

Implementation tests alone do not authorize rollout.
