# AI-ORD-01 Exploratory Acceptance — 2026-09-08

## Verdict

**COMPLETE WITH FOLLOW-UP**

The customer order-cancellation slice is implemented and passed its focused
live acceptance in the disposable demo stack. The Agent prepares a durable
AI-CONF-01 Risk Level 3 action, and only a successful single-use confirmation
submits the existing customer cancellation command to Order Service. Order
Service remains authoritative for cancellation, inventory compensation, and
the local demo refund.

The Agent order-mutation flag was enabled only for acceptance and restored to
its independent default-off state afterward.

## Live environment

- Buyer actor: `01D00000000000000000000002`
- Second authenticated actor: `01D00000000000000000000001`
- Cancelled demo order: `01M1ZJ0F87XB2NS75DNM2SJT4F`
- Final confirmation: `01M20C71AFCHK960QZKCEX5RTG`
- Final Agent image: `sha256:b096ac6f9e936618adbc41b7073df44b35f09c453d62590f2dce1605e2caaddc`
- Final frontend image: `sha256:1dc0c035579779003c60bcef55d5eee6d428e02b10132f5de75c6fffe0f5d2d3`
- Final action key: `agent-action-01M20C71AFCHK960QZKCEX5RTG`

Internal identifiers appear only in this engineering evidence. They were not
rendered in the customer conversation.

## Live walkthrough

| Case | Result | Authoritative evidence |
| --- | --- | --- |
| Informational eligibility | PASS | “Can my latest order still be cancelled? Please only tell me; do not cancel it.” ran owned order reads, returned eligibility, created no confirmation, and issued no command. |
| Explicit preparation | PASS | “Cancel my latest order.” ran `list_my_orders` and `preview_my_order_cancellation`, then created one Risk Level 3 `PENDING` record. |
| Exact binding | PASS | Actor, Agent session, workflow, capability/version, action, order/version, target fingerprint, 18.25 USD financial fact, action fingerprint, and action key were persisted before consent. |
| Ambiguous “Maybe” | PASS | No claim or tool execution; the same pending confirmation and controls remained. |
| Decline | PASS | “No” transitioned `PENDING -> CANCELLED`; no Order Service cancellation row was created. |
| Yes after decline | PASS | Explicit terminal response; zero model and cancellation-tool calls. |
| No pending confirmation | PASS | “Yes” in a fresh conversation ran no action and asked what marketplace item to check. |
| Browser refresh/reconnect | PASS | One summary and one control pair were restored; no second confirmation or automatic execution occurred. |
| Agent restart/recovery | PASS | The same confirmation survived Agent Service restart and remained the only pending record. |
| Cross-user/cross-conversation attempt | PASS | The second customer’s “Yes” could not claim the buyer’s record; the buyer confirmation remained `PENDING` and no cancellation command ran. |
| Expiry | PASS | Confirmation `01M20AZE5T6AVEQM70E138R6T6` materialized as `EXPIRED` after its deadline; controls disappeared and execution counts remained zero. |
| Confirm | PASS | The final “Yes” used zero model calls and transitioned `PENDING -> CONFIRMED -> CONSUMED` in one invocation. |
| Exact-once execution | PASS | Exactly one successful `cancel_my_order` audit row and one Order Service cancellation request exist. |
| Replay Yes | PASS | Returned “already been used” with zero model calls; tool and cancellation-request counts stayed at one. |
| Order cancellation | PASS | Order Service owns buyer `01D…002`; expected version was 0, request completed, and the order became `CANCELLED` at version 2. |
| Stable idempotency | PASS | `order_cancellation_commands` persisted the exact Agent action key, expected version 0, one request hash, state `COMPLETED`, and HTTP 200. |
| Inventory compensation | PASS | One `inventory_cancellation_restocks` row completed and restored quantity 1. |
| Local demo refund | PASS | One full `FAKE_LOCAL_DEMO_V1` refund for 18.25 USD succeeded with reconciliation `IN_SYNC`; one provider attempt succeeded. |
| History and privacy | PASS | Refresh restored each turn exactly once; no private binding, address, provider reference, refund ID, or cancellation request ID appeared in customer text. |
| AI-COM-01 regression | PASS | The buyer cart remained unchanged at one item throughout order cancellation. |
| Browser console | PASS | No warning or error entries after final refresh. |

## Durable contract evidence

The consumed record bound:

- workflow `CUSTOMER_ORDER_CANCELLATION`;
- capability `cancel_my_order` version `customer-order-cancellation-v1`;
- action `CANCEL_ORDER`, Risk Level 3;
- actor and Marketplace Agent session;
- exact order ID, version `0`, and snapshot fingerprint;
- exact total `18.25` and currency `USD`;
- action fingerprint prefix `67bd5cc9fed9af38`; and
- application-generated action key
  `agent-action-01M20C71AFCHK960QZKCEX5RTG`.

The final confirmation reached `CONSUMED` with optimistic version 2. Its
transition table contains exactly `PREPARED`, `USER_CONFIRMED`, and
`CLAIMED_FOR_EXACT_ACTION`. The confirmation invocation and replay invocation
both used zero model tokens.

## Automated verification

- Complete Agent suite: **898 passed, 45 skipped, 345 subtests passed**
- MySQL confirmation integration: **3 passed**
- Focused Angular Agent UI/service tests: **28 passed**
- Final Agent image built successfully

The MySQL module proves atomic single-use consumption under concurrent claims,
actor/session isolation, expiry, exact argument and target binding, and
fail-closed behavior when resource version, policy, or authorization changes.
Focused unit/service coverage additionally exercises affirmative variants,
ambiguous consent, unsafe interruption, feature disablement, stale order
version, lost-response reconciliation, model exclusion from application-only
execution, and private-output filtering.

## Defects found and corrected during acceptance

1. An informational cancellation-eligibility question could be over-selected
   as a cancellation preview. The policy now requires explicit mutation intent,
   and the orchestrator safely downgrades an over-selected preview to an owned
   order read before producing deterministic informational text.
2. The hard cross-account detector missed “another customer’s latest order.”
   Its phrase coverage now permits ordinals and recency words between the
   foreign owner and protected cart/order resource, producing a zero-model
   privacy refusal.

Both corrections have focused regression tests and passed the final complete
suite. They do not weaken AI-POL-00, AI-COM-00, AI-COM-01, AI-CONF-01, or
AI-CHK-01.

## Post-review decline correction

An independent walkthrough subsequently reproduced one narrow terminal-response
regression with the natural decline `No, keep the order.` The durable state had
already moved to `CANCELLED`, but the phrase was not classified as an explicit
negative answer. The turn fell through to ordinary model handling, returned an
unrelated account-status response, and the frontend retained stale Yes/No
controls until refresh.

The correction keeps affirmative consent deliberately narrow while adding only
order-preserving negative variants after punctuation and whitespace
normalization. The service can therefore use its existing atomic decline path
and pass the resulting terminal projection to the deterministic order response.
The frontend uses the same narrow recognition so it immediately suppresses the
exact answered control while waiting for the authoritative terminal response.

The failed wording was first captured by backend service/parser tests and a
frontend parser test. Before the source correction, the focused characterization
failed in both runtimes; afterward it passed.

The live disposable-stack rerun used the repository's durable confirmation
harness because the authenticated buyer's remaining confirmed legacy order has
an immutable `NOT_ALLOWED` cancellation snapshot and cannot legitimately be
prepared through Order Service. The harness created only a non-executable,
actor/session-bound Agent confirmation and public pending-control projection;
it did not alter Order Service policy or order data. The exact browser wording
then produced:

- confirmation `01M20GKEG42ZXE0X7YPABWRPS8`:
  `PENDING -> CANCELLED` with reason `USER_CANCELLED`;
- deterministic response: “Okay — I won’t request cancellation of that order.
  The order was not changed.”;
- zero model input/output tokens and zero tools in the decline invocation;
- immediate Yes/No removal before refresh and no controls after refresh; and
- target order `01KZ3VAN5Z44XCS4D3WBWEA863` still `CONFIRMED`, version `0`,
  with zero cancellation requests.

No checkout, payment, order placement, cancellation request, inventory change,
refund, seller action, or Admin action occurred in this post-review rerun.

## Follow-up closure

The delayed/failed compensation branches now have a controlled service-level
recovery harness. It does not add a production fault-injection endpoint or
weaken the existing Order, Inventory, or Payment authority boundaries.

The recovery coverage proves that:

- an Inventory failure schedules a future retry, prevents an early refund, and
  retries with the same `cancel-inventory:<requestId>` identity;
- a Payment refund failure preserves the already-successful Inventory leg and
  retries with the same `cancel-refund:<requestId>` identity;
- a completed Inventory leg is not executed again during refund recovery; and
- the compensation and cancellation request are marked complete only after
  both independently idempotent legs succeed.

The two focused recovery cases passed, and the complete Order Service suite
passed with **237 tests, 0 failures, 0 errors, and 0 skipped**. The complete
Agent suite also remained green with **898 passed, 45 skipped, and 345
subtests passed**.

The two unrelated runtime health observations were also resolved:

- ZooKeeper's invalid `cub zk-ready localhost 2181 30` probe was corrected to
  the command supported by the installed image, then the existing container
  was recreated without replacing its volume. ZooKeeper and the Kafka broker
  are healthy.
- Product Service was using an unavailable external S3 configuration in the
  disposable environment. The current Product image passed all four listing
  media health tests. The combined local AI overlay now pins the disposable
  Product runtime to the supported `local-demo` storage mode, without changing
  general demo or production S3 configuration. After a no-override recreate,
  both `/actuator/health` and the `listingMedia` health group report `UP`; the
  latter identifies the check as `metadata-only`.

No environment file, migration, API contract, order, payment, refund,
inventory record, cart, or customer conversation was changed by this closure.

## Follow-up

- Production rollout still needs a deliberate cohort decision; the Agent
  order-mutation flag remains default off.
- A real external provider's delayed callback or outage can be tested only when
  that provider offers a safe sandbox failure control. The repository now
  covers the required retry and partial-progress contract without adding a
  production-visible failure switch.
- No Admin cancellation/refund, checkout, payment collection, order placement,
  seller action, return, or dispute capability was added to the Agent.
