# AI-POL-00 Customer Agent Capability and Safety Boundary

Status: implemented on the existing default-off Marketplace Agent V2 surface.

## Decision

Marketplace Agent V2 remains a model-first controlled ReAct agent. The model
may understand any request, but executable authority comes only from the
backend customer-capability registry. A prompt, conversation message, listing,
retrieved passage, or tool observation cannot add a capability.

The authenticated customer remains the actor. Agent Service obtains that actor
from the authenticated request and binds every session and invocation lookup to
it. Tool schemas do not accept a model-selected actor ID. Product Service
remains the owner of listing truth and independently receives the trusted actor
from the Agent adapter. The AI is not an authorization principal and has no
admin, moderation, finance, merchant-operation, system, SQL, shell, filesystem,
Kafka, generic HTTP, or arbitrary internal-endpoint capability.

## Executable policy

`MarketplaceCustomerCapabilityBoundary` is the single source of capability
metadata for the Marketplace customer surface. It records surface, family,
risk, marketplace-state mutation, and confirmation requirements. The same
boundary determines the provider-visible schemas, validates model proposals,
and rechecks execution. Unknown tools, disabled families, and surface mismatch
fail closed before dependency I/O.

Risk levels are:

| Level | Meaning | Current executable examples |
| --- | --- | --- |
| 0 | Conversation only | Natural terminal response; no tool |
| 1 | Read | Availability, search, listing detail |
| 2 | Reversible low-risk state | Refined-search confirmation state; seller information collection; own-cart add/update/remove |
| 3 | Consequential customer transaction | Exact confirmed whole-cart submit under AI-CHK-01 |
| 4 | Forbidden customer authority | Admin, enforcement, finance-admin, cross-actor, seller-operation capabilities |
| 5 | Prohibited or unsafe abuse | Fraud, payment bypass, account takeover, authorization bypass |

Most Level 2 tools mutate only Agent session state. AI-COM-01 adds the narrow
exception of reversible mutations to the authenticated customer's own cart.
It does not mutate listing, payment, checkout, order, refund, or enforcement
state.

## Customer capability matrix

| Capability | Customer policy | Confirmation | Ownership check | Risk | State |
| --- | --- | --- | --- | --- | --- |
| `check_availability` | Allowed | No | Product adapter uses trusted actor | 1 | Existing |
| `search_listings` | Allowed | No, except an explicit customer-requested refined-search confirmation | Product adapter uses trusted actor | 1 | Existing |
| `get_listing` | Allowed only for a listing referenced in session context | No | Session reference plus Product revalidation | 1 | Existing |
| `request_confirmation` | Allowed for a materially changed refined search | It creates confirmation; it is not itself a commerce confirmation | Actor/session bound | 2 | Existing |
| `collect_listing_information` | Allowed to prepare seller information | No | Actor/session bound | 2 | Existing; does not create or publish |
| Listing customer-service retrieval | Separate authenticated listing-scoped surface | No mutation | Actor/listing session bound | 1 | Existing, separate runtime |
| Seller listing proposal/review/apply | Not a Marketplace customer tool | Explicit seller review and selected-value apply | Seller authorization and Product version checks | N/A | Existing, separate proposal architecture |
| `get_my_cart` / view own cart | Allowed read-only | No | Delegated bearer plus Order-owned actor derivation | 1 | Existing: AI-COM-00; default-off family |
| `list_my_orders` / `get_my_order` | Allowed read-only | No | Delegated bearer plus Order-owned buyer predicate | 1 | Existing: AI-COM-00; default-off family |
| View own return/refund status | Future customer capability | No | Required | 1 | Planned: AI-COM-00 |
| `add_to_my_cart` / `update_my_cart_quantity` / `remove_from_my_cart` | Allowed for an explicit unambiguous own-cart action | No | Delegated bearer plus Order-owned actor derivation, current cart version, Product and inventory revalidation | 2 | Existing: AI-COM-01; separate default-off family |
| `prepare_my_checkout` | Allowed for the whole current business cart | Creates exact confirmation | Delegated bearer plus Order/Inventory ownership and validation | 2 | AI-CHK-01; default-off family |
| `get_my_checkout` | Allowed for an already referenced owned checkout | No | Delegated bearer plus Order-owned buyer predicate | 1 | AI-CHK-01; default-off family |
| `submit_my_checkout` | Application invocation only after exact durable consume | Required | Current Agent policy plus Order/Inventory/Payment revalidation | 3 | AI-CHK-01; default-off family |
| Cancel eligible own order | Future customer capability | Required | Required with current-state validation | 3 | Planned: AI-ORD-01 |
| Create return or submit refund request | Future customer capability | Required | Required with current-state validation | 3 | Planned: AI-RET-01 |
| Submit report/support ticket | Not registered | N/A | Contract required | N/A | Planned/unresolved; never fabricated |
| Issue or force refund directly | Forbidden | N/A | N/A | 4 | Prohibited |
| View/change another user's account/order | Forbidden | N/A | N/A | 4/5 | Prohibited |
| Publish/edit listing or seller inventory | Forbidden on customer surface | N/A | N/A | 4 | Prohibited |
| Fulfill/ship orders or manage a business | Forbidden on customer surface | N/A | N/A | 4 | Prohibited |
| Suspend/ban/restrict a user, seller, or business | Forbidden | N/A | N/A | 4 | Prohibited |
| Approve/reject business or listing | Forbidden | N/A | N/A | 4 | Prohibited |
| Admin removal, enforcement, appeals, or cases | Forbidden | N/A | N/A | 4 | Prohibited |
| Change payment/refund/role/permission state | Forbidden | N/A | N/A | 4 | Prohibited |
| Arbitrary HTTP, service endpoint, SQL, shell, filesystem, Kafka, outbox, reindex, reconciliation, flags, secrets, or infrastructure | Forbidden | N/A | N/A | 4 | Prohibited |

Remaining planned entries are documentation only. AI-COM-00 added only the
three read tools above. AI-COM-01 adds only the three reversible own-cart
commands through existing Order APIs. AI-CHK-01 separately grants only exact
confirmed whole-cart submission; it grants no provider/outcome control, direct
payment/order state mutation, refund, cancellation, seller, or admin authority.

## Confirmation contract as implemented

AI-CONF-01 makes the Agent-owned `agent_confirmations` row authoritative for
confirmation state. The current provider-visible `request_confirmation` tool
still prepares only `RUN_REFINED_SEARCH`; it validates and stores the exact
strict `SearchListingsArguments` without adding a consequential commerce tool.
Session JSON is now a display/recovery projection rather than execution
authority.

| Binding | Current guarantee |
| --- | --- |
| Actor | Every lookup and transition includes authenticated `actor_user_id` |
| Conversation/session | Confirmation is bound to one actor-owned V2 session |
| Invocation/workflow | Originating invocation is mandatory; workflow ID is optional and immutable |
| Action/tool | Capability version and exact action are fingerprinted; today only `RUN_REFINED_SEARCH` is wired |
| Arguments | Canonical immutable JSON, structurally compared before consumption |
| Target resource/version | Typed target/version bindings are supported; refined search has no target resource |
| Financial facts | Typed amount/currency facts are supported; refined search has no financial fact |
| Expiration | Backend TTL is mandatory for Level 3+ and applied to current refined-search confirmations |
| Idempotency key | Dedicated unique action key is stable across retry/reconnect; atomic consume is single-use |

A bare yes/no cannot execute without a waiting actor/session interaction. An
unsafe turn invalidates a waiting interaction or cancels active seller
collection before field resolution, provider work, or tool execution. The
expiry, originating-invocation, resource-version, financial-fact, and
action-identity guarantees are now supplied by AI-CONF-01. Future Level 3
capability slices must still define owning-service preparation, authorization,
current-resource revalidation, and domain idempotency before registration. See
`ai-conf-01-consequential-action-confirmation.md`.

## Safety and failure behavior

High-confidence requests involving stolen payment credentials, payment bypass,
account takeover, cross-actor order mutation, authorization bypass, or direct
admin API use are narrow application-owned Level 5 safety stops. They produce a
canonical refusal, perform zero model and marketplace-tool work, cancel a
waiting workflow when supported, and persist a scope decision with risk level,
policy decision, reason code, and cancellation outcome. This safety gate is not
the normal intent router; ordinary, ambiguous, and allowed marketplace intent
continues through the model-first loop.

Unknown, unavailable, disabled, malformed, unauthorized, workflow-forbidden,
duplicate, and step-budget-exhausted proposals remain structured observations.
Rejected authority attempts persist only bounded policy metadata; raw
arguments, prompts, private reasoning, secrets, and payment data are excluded.
Repeated proposals cannot register a tool or bypass the five-decision budget.

Marketplace and retrieved text is untrusted data. It is supplied only inside
application context and cannot alter the provider tool schemas, the capability
registry, or execution policy. Tool observations remain data rather than
higher-priority instructions.

## Activation and kill switch

The established server-side gates remain authoritative and default false:

- `AGENT_MARKETPLACE_V2_API_ENABLED`
- `AGENT_MARKETPLACE_V2_PROVIDER_ENABLED`
- `AGENT_MARKETPLACE_V2_PRODUCT_TOOLS_ENABLED`
- `AGENT_MARKETPLACE_V2_COMMERCE_READS_ENABLED` (independently default false)
- `AGENT_MARKETPLACE_V2_CART_MUTATIONS_ENABLED` (independently default false)
- `AGENT_MARKETPLACE_V2_CHECKOUT_ENABLED` (independently default false)
- `AGENT_MARKETPLACE_V2_KILL_SWITCH_ENABLED` (must be clear)

The capability boundary also supports server-side family removal; a disabled
family is absent from provider schemas and rejected again at policy/execution.
AI-COM-00 supplies the first concrete commerce-family flag and requires
`ORDER_SERVICE_URL` when enabled. Disabling the family removes its tools from
provider schemas and blocks policy/execution without altering normal non-AI
marketplace flows.

AI-COM-01 supplies a separate cart-mutation family and flag. Enabling it also
requires the API, commerce reads, and an Order adapter because each write first
reads the authoritative cart version. The family is rechecked at provider
schema, proposal-policy, and final pre-I/O execution boundaries. Read tools can
remain enabled while mutation tools are removed.

## Architecture and compatibility

The AI-POL-00 generalization is limited to a typed metadata layer around the
existing V2 registry. Responses API use, one proposal per decision,
`MAX_AGENT_STEPS = 5`, SSE behavior, invocation-first persistence, Product
adapters, session ownership, correlation IDs, and separate customer-service and
seller-proposal architectures remain unchanged. AI-CONF-01 subsequently adds
the forward-only Agent V21 confirmation tables and optional pending-interaction
display fields; it does not change a Java service, Gateway/Auth contract, or
normal marketplace endpoint.

AI-COM-00 adds the forward-only Agent V19 tool-audit allowlist and an in-memory
delegated Order adapter. AI-COM-01 reuses that adapter and existing Java Order
contracts, adds the forward-only V20 audit allowlist, and adds three mutation
activity names to the Angular V2 parser. Neither slice changes a Java service,
Gateway/Auth contract, public API field, or normal cart UI behavior.

## Intentional follow-up

- Require each future Level 3 slice to use AI-CONF-01 and define its owning
  service's current resource/version, financial facts, authorization, and
  idempotent command contract before capability registration.
- Add only approved customer report/support adapters before offering those
  actions through AI.
- General marketplace policy RAG and other actor-private account tools remain
  unavailable. Cart/order reads and cart mutations exist only behind their
  independent AI-COM family gates; the agent must abstain when required
  grounding is absent or a target is ambiguous.
- Admin AI remains a future separately authenticated and permission-scoped
  surface. Customer capabilities must never be reused as an elevation path.
