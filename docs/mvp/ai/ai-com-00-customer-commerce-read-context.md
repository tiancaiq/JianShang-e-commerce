# AI-COM-00 Customer Commerce Read Context

Status: implemented and verified offline on 2026-09-01. Runtime activation
remains default off.

## Scope

AI-COM-00 adds three read-only Marketplace Agent V2 tools:

- `get_my_cart {}`
- `list_my_orders {cursor?, limit}` with `limit` restricted to `1..10`
- `get_my_order {orderId}`

It does not add cart mutation, checkout, payment, cancellation, return, refund,
shipment mutation, seller operation, or admin authority. The existing
AI-POL-00 capability boundary remains the source of truth for provider
exposure, proposal validation, and the final pre-I/O check.

## Owning contracts reused

Order Service remains the sole runtime owner of both facts:

- the current Redis cart through authenticated `GET /api/v1/cart`;
- durable buyer orders through authenticated `GET /api/v1/orders` and
  `GET /api/v1/orders/{orderId}`.

Agent Service does not query Order-owned Redis or MySQL, copy order truth into
an Agent database, or use OpenSearch as current commerce truth. No new Order
Service or Gateway endpoint was required.

The Gateway/BFF remains the public entry point and relays the customer access
token to Agent Service. For a commerce tool invocation, Agent Service delegates
that same bearer credential directly to the existing protected Order Service
customer endpoint. The credential exists only in memory for that invocation.
It is not a model argument, session field, observation, provider context,
message action, tool-audit hash, log field, or error payload.

Order Service independently validates the JWT, derives the subject, resolves
the buyer, and predicates every order read by that buyer. Missing and
cross-buyer order detail use the same `404 ORDER_NOT_FOUND` result. Agent
Service normalizes both to the same non-revealing `ORDER_NOT_FOUND`
observation.

## Normalized observations

The model receives only answer-useful allowlisted facts.

Cart observations contain version/expiry, item and quantity counts, currency
totals, and each item's listing ID, title, optional store name, quantity,
observed price, and currency. `observedPrice` is advisory cart state and must
not be described as a paid or checkout-authoritative price.

Order-list observations contain newest-first order ID, order/payment status,
total, currency, created/updated timestamps, and customer-facing store/status
summaries. Internal business and business-order IDs are removed.

Order-detail observations contain the same order header plus store/status
groups, purchase-time item title/listing ID/quantity/unit price/line total,
safe timeline entries, and safe carrier/service/shipment status. They exclude:

- shipping address and address label;
- recipient name and phone;
- postal data;
- tracking number and shipment ID/source/version;
- business, store, and business-order IDs;
- policy versions and internal cancellation/payment/provider fields.

Purchase-time order item price/title snapshots remain historical order facts.
A later `get_listing` call reads current public Product state and must be
described separately rather than overwriting or relabeling the order snapshot.

## Multi-turn reference behavior

Successful observations are stored in the existing bounded V2 message-action
format and restored as structured references. The model can resolve `last
order`, `second one`, or `that order` from the ordered prior observation, then
must call `get_my_order` again for current status. A prior private observation
cannot satisfy the current turn's `PRIVATE_TOOL` grounding check.

An order-detail item listing ID becomes an allowlisted reference for the
existing `get_listing` tool. This supports a later current-listing question
without treating the immutable order snapshot as current catalog truth.

The current order-list contract has no item titles. The Agent therefore does
not perform an N+1 detail scan merely to locate an order by an item phrase. If
the available structured references do not identify exactly one order, the
model asks one focused clarification or drills into a customer-selected order.

## Capability gate and failure behavior

`AGENT_MARKETPLACE_V2_COMMERCE_READS_ENABLED` is independently default false.
When false, the `CUSTOMER_COMMERCE_READ` family is absent from provider tool
schemas and is rejected again by proposal policy and execution policy. When
true, `ORDER_SERVICE_URL` is required at startup. The existing V2 API,
provider, Product-tool, persistence, and kill-switch gates still apply.

Calls use the existing bounded dependency timeout, disable redirects and
retries, cap accepted response bytes, send only `Authorization`,
`X-Correlation-Id`, and `Accept`, and reduce upstream/auth/schema failures to
stable observations. Raw downstream response bodies and credentials do not
cross the adapter boundary.

The tool registry adds a forward-only V19 check-constraint migration so only
the three new read tool names can be written to `agent_tool_calls`. No commerce
payload or credential is stored in that audit table.

## Verification

Offline Agent tests cover strict actor-free schemas, three-layer family
removal, credential non-persistence, privacy normalization, pagination,
cross-actor non-disclosure, multi-turn re-fetch, stale-fact rejection,
purchase-snapshot-to-current-listing revalidation, scope classification,
audit allowlisting, and the AI-COM-00 eval fixture.

Existing Order Service tests remain authoritative for actor-derived cart
ownership, buyer-derived list/detail access, buyer predicates, and the
privacy-safe `ORDER_NOT_FOUND` contract.

## Deferred work

- Return/refund/cancellation detail is not exposed by these normalized tools.
- Item-phrase lookup across order history remains unsupported because the
  approved list contract has no item summary and AI-COM-00 adds no N+1 scan.
- The Angular V2 contract allowlists the three activity names, but no
  UI-specific cart/order attachment or action type is added.
- All commerce mutations remain separate future slices with stronger
  confirmation, version, idempotency, and compensation contracts.
