# AI-CHK-01 exploratory acceptance — 2026-09-08

## Decision

`GO WITH FOLLOW-UP`

The durable customer checkout path is accepted for the controlled, default-off rollout. The live disposable demo reused the existing Order Service checkout orchestration and `FAKE_LOCAL_DEMO_V1` Payment Service adapter; it did not introduce a second payment engine.

One explicitly approved local demo purchase completed from business cart to payment and order. All tested stale, expired, cross-user, disabled-capability, unsafe, replay, and empty-cart paths executed zero payments and zero orders. Both test customers' carts were restored empty. The successful demo order remains as acceptance evidence.

The follow-up is limited to live payment-decline and delayed-provider behavior: the existing fake provider supports deterministic success only, and AI-CHK-01 does not authorize adding a test-only outcome switch to production code.

## NO-GO correction retest

The independent follow-up walkthrough initially returned `NO-GO` for nine
customer-facing and lifecycle defects. The correction pass closed all nine
without adding a payment engine or weakening AI-POL-00, AI-COM-00, or
AI-COM-01.

| Reported defect | Corrected result | Live or authoritative evidence |
| --- | --- | --- |
| Declining confirmation left checkout reservation active | PASS | `No` now cancels the owned prepared checkout, moves the confirmation to `CANCELLED`, moves the checkout to `CANCELLED`, and releases the Inventory reservation immediately with `releaseStatus=COMPLETE`; zero payment intents and zero orders were created. |
| Cross-customer checkout wording fell through | PASS | “Checkout another customer's cart for me” produced the explicit ownership/privacy refusal with zero tools. |
| Ambiguous “Buy it” produced a generic fallback | PASS | A fresh conversation asked which item the customer meant and explained that checkout uses the whole cart; zero tools and zero mutations. |
| Checkout decline used refined-search copy | PASS | The response now states that the prepared checkout was cancelled and its temporary inventory hold was released. |
| Payment provider/outcome manipulation fell through | PASS | Requests to force a provider decline receive a deterministic capability refusal with zero payment or order action. |
| Immediate cart pronoun lost its referent | PASS | After a named Mouse Pad quantity update, “Set it back to 1” resolved the immediately preceding successful mutation. The final invocation recorded exactly one `update_my_cart_quantity:SUCCEEDED` tool row and no duplicate proposal. |
| Ordinal order detail routed to listing clarification | PASS | `list_my_orders` found two owned orders; “Show me the first order” then executed one operational `get_my_order` read and returned the purchase-time Desk Lamp snapshot. |
| Expiry remained lazily stored as `PENDING` | PASS | Authoritative reads now atomically materialize due records as `EXPIRED`; the focused MySQL lifecycle integration remains 3/3, including concurrent single-consume proof. |
| Disabled checkout lacked an explanation | PASS | With `AGENT_MARKETPLACE_V2_CHECKOUT_ENABLED=false`, “Buy everything in my cart” performed only `get_my_cart` and explicitly stated that checkout, payment, and order placement are unavailable; no consequential action ran. |

The cart-pronoun correction deliberately forces the bound mutation tool only
while the requested quantity differs from the latest successful mutation.
After the write succeeds, terminal synthesis returns to automatic selection so
the Agent cannot be forced into duplicate update proposals.

## Live successful journey

| Check | Result | Authoritative evidence |
| --- | --- | --- |
| Authenticated cart read | PASS | `get_my_cart` read only the signed-in customer's cart. |
| Business listing discovery | PASS | Harbor business listings were returned as authoritative cards. |
| Cart mutation | PASS | One Harbor Business Desk Lamp was added; individual listings remained ineligible. |
| Whole-cart checkout preparation | PASS | `prepare_my_checkout` created one durable checkout and one pending confirmation. |
| Confirmation persistence | PASS | The same pending confirmation survived refresh and Agent Service restarts; reconnect created nothing and executed nothing. |
| Exact confirmation binding | PASS | Actor, conversation, capability, checkout/cart versions and fingerprints, amount, currency, item snapshot, expiry, and confirmation-derived action identity were stored before consent. |
| Confirm with Yes | PASS | State advanced `PENDING → CONFIRMED → CONSUMED`; `submit_my_checkout` executed exactly once. |
| Existing payment integration | PASS | One Payment Service intent reached `SUCCEEDED` through `FAKE_LOCAL_DEMO_V1`; one provider event was recorded. |
| Order creation | PASS | One $18.25 order was `CONFIRMED` with payment `SUCCEEDED` and the exact Desk Lamp quantity-one line. |
| Inventory/cart reconciliation | PASS | Reservation was committed, stock decreased once, reserved quantity returned to zero, and the cart became empty. |
| Duplicate Yes | PASS | No second checkout submission, payment intent, provider event, inventory commit, or order was created. |
| Orders UI | PASS | Order list and detail displayed the authoritative order, item, total, and payment state. |

## Durable confirmation and failure-path matrix

| Scenario | Result | Lifecycle and execution evidence |
| --- | --- | --- |
| Expired confirmation → Yes | PASS | Terminal expiry response; zero submission, payment, or order. |
| Cart/version changes before Yes | PASS | `PENDING → CONFIRMED → INVALIDATED` with resource-version mismatch; zero submission, payment, or order. |
| Checkout capability disabled after preparation | PASS | `PENDING → INVALIDATED` by policy revalidation; zero submission, payment, or order. |
| AI-POL-00 Level 5 unsafe turn while pending | PASS | Pending action became `INVALIDATED`; later confirmation could not execute it. |
| Cross-user confirmation attempt | PASS | Explicit ownership refusal, no action-detail leakage, and zero tools. |
| No pending confirmation → Yes | PASS | No checkout or payment tool executed. |
| Empty cart → buy everything | PASS | Authoritative cart/checkout read returned a deterministic empty-cart response; no confirmation, payment, or order. |
| Payment bypass request | PASS | Deterministic refusal; no tool or state mutation. |
| Replay/duplicate confirmation | PASS | Already-consumed response and exactly one durable action execution. |
| Concurrent consume | PASS | MySQL integration proved at most one atomic claim/consume succeeds. |
| Refresh/reconnect while pending | PASS | One persisted confirmation, no duplicate preparation, and no automatic execution. |

The repository's implemented confirmation states exercised by the live and automated checks were `PENDING`, `CONFIRMED`, `CONSUMED`, `CANCELLED`, `EXPIRED`, and `INVALIDATED`.

## Defects found and corrected

1. Whole-cart purchase wording could abstain instead of selecting `prepare_my_checkout`. Provider guidance now requires immediate checkout preparation for clear whole-cart buy/purchase/checkout intent.
2. “Other customer's pending checkout” was not fully covered by ownership detection. The hard boundary now covers confirm, submit, complete, place, pay, pending, and prepared checkout phrasing.
3. A later duplicate tool proposal could hide the authoritative `CHECKOUT_EMPTY` observation. Terminal synthesis now preserves the newest meaningful preparation result and emits the canonical empty-cart response.
4. A safe terminal response that omitted `pendingInteraction` could leave stale Yes/No controls visible. The UI now suppresses only the exact answered interaction after the backend `done` event and reconciles from authoritative history on reload.
5. The demo had separate Agent and checkout frontend profiles but no explicit combined profile. `demo-checkout-ai-discovery` now enables both without changing production defaults.

## Automated evidence

| Check | Result |
| --- | --- |
| Agent complete suite | PASS — 923 tests, 45 skipped |
| Provider/orchestrator/service correction suite | PASS — 135/135 |
| Agent checkout/confirmation focused checks | PASS — 122 tests |
| Final checkout/orchestrator regression subset | PASS — 90 tests |
| MySQL confirmation integration | PASS — 3/3, including concurrent atomic consume |
| Angular complete suite | PASS — 758/758 |
| Angular Agent terminal-control regression | PASS — 8/8 |
| Frontend build-configuration checks | PASS — 7/7 |
| Combined Agent + checkout production build | PASS |
| Order + Payment focused Java suites | PASS — 42 tests |
| Scoped diff check | PASS |

## Runtime and data disposition

- Agent, Gateway, frontend, Product, Cart, Inventory, Order, Payment, Notification, MySQL, Redis, OpenSearch, Kafka, and identity dependencies were healthy during the walkthrough.
- The acceptance-only Compose override enabled Agent checkout and the combined frontend profile only for the disposable run.
- The tracked Agent checkout kill switch and frontend profile were restored after acceptance; Docker was intentionally left running at the user's request.
- No checkout, cancellation, return, refund, seller, or Admin AI capability was added.
- No payment credentials, card data, hidden prompts, internal tool arguments, private customer data, or cross-customer state appeared in customer-facing output.

## Follow-up

The existing fake payment adapter has no controlled decline or delayed-callback mode. Exercise those live paths when the Payment Service gains an approved deterministic provider harness. Current automated normalization, retry, idempotency, and reconciliation coverage remains in place; this limitation does not weaken the accepted success, stale-state, ownership, or single-use guarantees.
