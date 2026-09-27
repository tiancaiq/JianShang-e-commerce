# AI-COM-01 Customer Cart Mutations

Status: implemented behind a default-off Marketplace Agent V2 capability flag.

## Scope and tools

AI-COM-01 adds three narrow Marketplace customer tools:

| Tool | Model arguments | Meaning |
| --- | --- | --- |
| `add_to_my_cart` | `listingId`, `quantity` | Set a listing's cart quantity, including adding a new line |
| `update_my_cart_quantity` | `listingId`, `quantity` | Replace one existing line's quantity |
| `remove_from_my_cart` | `listingId` | Remove one existing line |

All three belong to `CUSTOMER_CART_MUTATION` on the
`MARKETPLACE_CUSTOMER` surface. They are Risk Level 2 reversible marketplace
mutations and require no ordinary confirmation. They do not create checkout,
payment, platform order, return, refund, support, seller, or admin actions.

## Actor boundary and authorities

The model never supplies a user, buyer, cart-owner, business, or authorization
field. Agent Service forwards the authenticated request's delegated bearer
outside model arguments. Order Service independently derives the current actor
from the JWT subject and owns cart authorization and state.

Order Service is authoritative for the cart version, item/quantity limits,
idempotency record, and mutation result. Product Service remains authoritative
for the current active BUSINESS listing and current price, while Inventory
Service remains authoritative for initialized available stock. The existing
Order Cart service revalidates Product and inventory for add and quantity
update. Agent Service does not copy those rules.

Marketplace Agent V2 discovery searches both public individual and business
listings. Discovery does not imply cart eligibility: only an active public
BUSINESS listing may be added. An attempt to add an INDIVIDUAL listing is
revalidated by the owning commerce flow, returns `NOT_PURCHASABLE`, performs no
cart mutation, and receives a customer-facing refusal.

Conversation and persisted cart references are identifiers for grounding, not
current commerce truth. The adapter reads the current cart immediately before
each write and sends its current `If-Match` version. A stale search result or
old cart reference therefore cannot override owning-service validation.

## Mutation semantics

- Add accepts quantity 1 through 999. It sets the requested quantity; if the
  line already exists, it replaces rather than increments the quantity. The
  resulting line receives the current Product price observed by Order Service.
- Update accepts quantity 1 through 999, replaces the existing line quantity,
  and preserves its previously observed cart price under the existing contract.
- Remove deletes the existing line. Removing an absent line returns the
  existing `CART_ITEM_NOT_FOUND` contract, normalized to `NOT_FOUND`.
- Successful observations include the authoritative updated cart. Cart prices
  are advisory observed values and are not checkout or payment guarantees.

Owner errors are normalized to bounded reasons including `NOT_FOUND`,
`NOT_PURCHASABLE`, `OUT_OF_STOCK`, `INVALID_QUANTITY`, `CART_CONFLICT`,
`CART_LIMIT_EXCEEDED`, `IDEMPOTENCY_CONFLICT`, and
`CART_UPSTREAM_UNAVAILABLE`. Raw dependency bodies are not returned to the
model or customer.

## Idempotency, retry, and uncertainty

The action reference is the durable Agent invocation ID, not model input. The
Order idempotency key is deterministically derived as
`agent-cart-{invocationId}`. Order Service stores the response per actor and
request hash in the same atomic Redis mutation as the cart version update.
Same-key/same-request replay returns the stored result; changing the request
under that key is an idempotency conflict.

Agent Service permits only one cart mutation attempt per invocation. If the
transport loses a write response or receives a server error, it makes at most
one replay with the same idempotency key, request, and `If-Match` value. It
never invents a fresh key or blindly applies another write. A timeout before
the cart/version read performs no write.

If the same-key replay also fails, Agent Service performs a fresh authoritative
cart read. A visible requested state becomes `CART_MUTATION_RECONCILED`; an
unverified state becomes `OUTCOME_UNKNOWN`. The latter never produces a
success claim and asks the customer to inspect the cart before trying again.

The existing Order request hash includes the expected cart version. A later
outer response-retry keeps the same invocation-derived key, but its refreshed
version can make it a changed request; Order then returns
`CART_IDEMPOTENCY_CONFLICT`. This is deliberately not retried with a fresh key:
it cannot double-apply and the customer is told to check the cart. Exact
post-I/O recovery is handled inside the original adapter call, where the
original version and request are still available.

## Concurrency

Agent and normal UI writes share the existing Redis Lua contract. The script
atomically checks the actor-owned cart version, mutates the cart, increments
the version, refreshes TTL, and stores the idempotent response. Concurrent
writes using the same old version cannot both apply: one succeeds and the
other receives `CART_VERSION_CONFLICT`, normalized to `CART_CONFLICT`. Agent
Service does not automatically retry a conflict as a new action.

## Multi-turn grounding

Search results, cart reads, and the last successful mutation retain bounded
listing references. This supports “add the second one,” “make that three,” and
“remove it” when the target is unambiguous. Full cart snapshots, quantities,
prices, credentials, and dependency bodies are not retained as authority
between turns. Multiple plausible targets cause a clarification and zero
mutation. Marketplace content remains untrusted data and cannot add tools or
change capability policy.

## Activation and failure behavior

`AGENT_MARKETPLACE_V2_CART_MUTATIONS_ENABLED` defaults to false. Mutation tools
are supplied only when the Marketplace V2 API, commerce reads, and mutation
flag are enabled, the kill switch is clear, and `ORDER_SERVICE_URL` is
configured. Disabling the family removes provider schemas, rejects proposals,
and rejects final execution before Order I/O. Commerce read tools remain an
independent family.

Tool activity continues over the existing SSE and generic message contracts.
Agent V20 allowlists the three audit tool names. Audit arguments retain bounded
listing/operation/quantity metadata and a hash of the action reference; bearer
credentials, request/response bodies, prompts, and private reasoning are not
persisted.

## Verification coverage

Automated coverage exercises capability exposure at all three gates, actor-free
schemas, own-cart API shapes, quantity validation, add/replace/update/remove,
absent and unavailable items, authoritative results, stable replay,
post-write reconciliation, unknown outcomes, duplicate proposals, optimistic
concurrency and Redis atomicity, multi-turn references, ambiguity, unsafe and
unsupported actions, persistence/audit privacy, Agent step limits, Angular
activity parsing, and the AI-COM-01 eval fixture.

## Deferred capabilities

AI-CONF-01 now supplies the reusable confirmation foundation, but does not
change these Risk Level 2 cart commands. AI-CHK-01 checkout, AI-ORD-01 order
cancellation, AI-RET-01 returns/refunds, support/report mutations, and a future
separately authenticated Admin AI remain deferred. No consequential commerce
capability is completed by this slice.
