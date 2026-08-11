# V2 payment sandbox provider selection and adapter contract

Status: **Approved for Stripe test-mode implementation on 2026-08-11**
Scope: `PAY-01`, `PAY-02`, `PAY-03`, `ORD-03`, `V2-RET-01`, and
`V2-COM-RC-01`
Decision owner: Commerce product/engineering owner
Last reviewed: 2026-08-11

## 1. Decision requested

Approve **Stripe in sandbox mode** as the first real external payment provider,
implemented behind a provider-neutral Payment Service adapter. Keep
`FAKE_LOCAL_DEMO_V1` as the default local and CI provider.

The commerce owner approved Stripe sandbox and the asynchronous refund
compatibility work on 2026-08-11. This approval does not include live mode,
Stripe Connect, payouts, transfers, or production enablement.

The recommendation is Stripe because its PaymentIntent lifecycle most closely
matches the existing internal intent lifecycle, its webhook and idempotency
semantics fit the existing Payment Service controls, its Payment Element keeps
card data out of the platform, and Connect documents separate charges and
transfers for multi-party marketplace payments. Adyen is a credible enterprise
alternative. PayPal Complete Payments is viable, but its order/capture model and
partner onboarding make it a less direct first fit.

## 2. Preconditions and current architecture

The prerequisite Commerce release candidate is merged on `dev` at
`27f57f03b7baf71d31e776bfe57704c7ed6315c2`. The post-merge manual Commerce RC
workflow run `31471614637` passed all five jobs, including the MySQL concurrency
and real runtime/recovery/accounting jobs.

The implementation already establishes these boundaries:

- Order Service owns checkout, order confirmation, cancellation, returns, and
  their business state machines.
- Payment Service owns payment intents, provider attempts, verified provider
  events, refunds, idempotency records, and the payment outbox.
- Checkout creates a server-priced Payment intent through Order Service. The
  browser cannot supply an authoritative amount or currency.
- Provider completion is authoritative only after Payment Service verifies and
  deduplicates a webhook. Browser completion is not authoritative.
- `payment.succeeded` and `payment.failed` are emitted through the Payment
  transactional outbox. Order verifies the immutable checkout snapshot before
  confirming an order.
- Full cancellation refunds derive amount and currency from the succeeded
  Payment intent. Return refunds derive their amount from the immutable order
  group subtotal. Neither client can choose a refund amount.
- Notifications consume completed Order outcomes; they do not interpret
  provider payloads.
- Provider references, never card data, are stored.

The current adapter is intentionally local-demo shaped:

- `PaymentProvider` supports create intent, action reference, and refund.
- `DeterministicFakePaymentProvider` returns `REQUIRES_ACTION` and synchronous
  successful refunds.
- `PaymentWebhookService`, the parser, and HMAC verifier are currently specific
  to the fake provider and two fake event types.
- Payment and return-refund tables constrain refunds to `SUCCEEDED`; the
  cancellation path also assumes an immediate successful refund response.
- No runtime provider selector exists. The single fake provider bean is
  injected directly.

Current mechanics by concern:

| Concern | Merged implementation |
|---|---|
| Provider abstraction | `PaymentProvider`: `providerName`, `createIntent`, `actionReference`, and `refund` |
| Local provider | `DeterministicFakePaymentProvider`, code `FAKE_LOCAL_DEMO_V1`; deterministic intent/action/refund references; no network |
| Intent states | `CREATED`, `REQUIRES_ACTION`, `PROCESSING`, `SUCCEEDED`, `FAILED`; succeeded/failed are terminal and transitions are allow-listed |
| Intent creation | Persist internal `CREATED` intent and request-hash idempotency record before provider invocation; provider result creates attempt/history and completes the idempotency response |
| Fake webhook | `POST /api/v1/webhooks/payments`, exact raw body, `X-MSB-Signature`, timestamped HMAC-SHA256 with default five-minute tolerance |
| Event model | Fake parser accepts only `payment_intent.succeeded` and `payment_intent.failed`; verified events have unique `(provider,event_id)`, payload hash, signature timestamp, application outcome, and provider-reference correlation |
| Attempts/history | Payment-owned intent, status-history, and attempt tables retain normalized state and safe error; provider references are unique within provider |
| Outbox | Payment transition and `payment.succeeded`/`payment.failed` outbox insert are atomic; dispatcher has claim ownership, retry/backoff, and terminal metadata |
| Refunds | Payment-owned cancellation and return-refund records have unique request/idempotency/provider-reference constraints, but current schemas and attempts allow only synchronous `SUCCEEDED` |
| Cancellation | Order restores inventory, then calls Payment with intent/order/cancellation IDs; Payment derives the full succeeded-intent amount/currency and returns one deterministic refund |
| Group return | Order decides received/disposition and calls Payment with immutable return/group authority; Payment enforces currency and cumulative amount and creates one partial deterministic refund |
| Checkout boundary | Angular calls Order; Order calls Payment through an internal authenticated endpoint with server checkout snapshot and deterministic request key |
| Confirmation boundary | Payment emits normalized outbox event; Order validates immutable bindings and confirms exactly once; the browser only polls the outcome |
| Frontend | Local-demo checkout creates an intent, invokes the explicitly gated demo completion endpoint, and polls confirmed order; it handles normalized action type/reference only |
| Gateway | Checkout stays Order-facing; no approved public provider webhook route is present in the Commerce overlay, and broad direct Payment exposure must not be used for the new route |
| Runtime controls | Intents, webhooks, demo completion, refunds, outbox dispatch/worker, and local HTTP transport are independently disabled by default and enabled explicitly in the RC overlay |
| Secrets/config | Runtime environment placeholders are used; provider/auth tokens are not committed or placed in shared constants, events, frontend bundles, or logs |

The real-provider slice must therefore generalize provider integration inside
Payment Service and extend refund persistence truthfully. It must not move
amount authority, order confirmation, cancellation, returns, or notification
policy into a provider adapter.

Contracts that remain completely unchanged:

- Cart, Inventory reservation, checkout snapshot, and confirmed-order APIs.
- Order's validation of payment amount, currency, buyer, checkout, reservation,
  and business-group bindings before exactly-once confirmation.
- Buyer and seller order APIs and authorization.
- Cancellation and return amount/disposition authority.
- The final-success `payment.refunded` meaning and downstream notification
  policy. It is emitted later for asynchronous providers, but still means the
  refund has actually succeeded.
- Notification payloads and read/unread behavior.

The Payment provider interface, webhook entry point, Payment-owned persistence,
refund progress representation, frontend action handling, Gateway route, and
runtime configuration require the contained extensions described below.

## 3. Candidate comparison

| Criterion | Stripe | Adyen | PayPal Complete Payments |
|---|---|---|---|
| Sandbox developer experience | Strong: test keys, test cards, CLI, dashboard, and documented 3DS/decline/refund cases | Strong, but test-account and merchant configuration are more enterprise-oriented | Strong sandbox, but marketplace/partner behavior requires seller onboarding and approval context |
| Java 21/Spring fit | Official Java SDK and conventional REST/idempotency model | Official Java API library and mature Checkout APIs | REST works from Java; official guides are less Java/Spring-centered than Stripe/Adyen |
| Angular/browser fit | Payment Element tokenizes in provider-controlled UI; client secret is the bounded browser capability | Web Drop-in/Components keep sensitive fields provider-controlled | JavaScript SDK Buttons/Card Fields support hosted payment UI |
| Payment lifecycle fit | `PaymentIntent` maps directly to requires-action, processing, succeeded, and failed | Payment result codes and AUTHORISATION webhooks map cleanly through normalization | PayPal Order plus Capture introduces an additional provider aggregate relative to the internal intent |
| Webhook security/reliability | Signed raw payload, timestamp tolerance, duplicate delivery guidance, retries, unordered delivery guidance | HMAC verification, async acknowledgement, retry and idempotency guidance | Signature verification is supported, with retries up to 25 times over three days |
| Provider idempotency | Explicit `Idempotency-Key` on POST requests | Explicit idempotency key on POST requests, retained at least seven days | `PayPal-Request-Id` on supported POST calls |
| Full/partial refunds | Both; refund may be pending, require action, succeed, fail, or be canceled | Both; submission is asynchronous and final outcome arrives by webhook | Both against captures; refund webhooks are available |
| Marketplace trajectory | Connect separate charges and transfers matches the existing declared funds-flow direction; marketplace onboarding remains a later scope | Adyen for Platforms and split transactions are strong enterprise marketplace capabilities | Multiparty supports seller onboarding and partner fees, but approval and account model add coupling |
| Main risk | Client secret handling, API-version pinning, Connect liability/accounting decisions | Greater operational/onboarding complexity for the first sandbox slice | Approval/onboarding coupling and greater translation between internal intent and provider order/capture states |

### Recommendation

Choose **Stripe sandbox** for the first adapter, subject to owner approval.
Choose Adyen instead if enterprise acquiring, unified platform settlement, or an
existing Adyen commercial relationship is a near-term business requirement.
Do not choose PayPal as the first adapter unless PayPal wallet coverage or an
existing PayPal partner relationship outweighs the additional order/capture and
seller-onboarding coupling.

This is not a production processor, pricing, geography, liability, or merchant-
of-record decision. Those require separate commercial, legal, tax, risk, and
operations approval.

## 4. Provider-neutral adapter contract to freeze

The adapter boundary belongs in Payment Service. Provider SDK types, JSON
objects, status strings, exceptions, and client secrets must not cross it.

```java
interface PaymentProviderAdapter {
    String providerCode();
    ProviderCapabilities capabilities();

    ProviderPaymentResult createPayment(ProviderPaymentCommand command);
    ProviderPaymentResult retrievePayment(String providerPaymentReference);

    ProviderRefundResult createRefund(ProviderRefundCommand command);
    ProviderRefundResult retrieveRefund(String providerRefundReference);

    VerifiedProviderEvent verifyAndParseWebhook(
            byte[] rawBody,
            Map<String, List<String>> headers,
            Instant receivedAt);
}
```

`cancelPayment` is deliberately omitted from the first contract because no
current checkout, cancellation, or return flow requests provider cancellation:
whole-order cancellation begins only after a succeeded payment and therefore
uses a refund. Add provider cancellation later only with an approved business
case; do not add an unused adapter method speculatively.

Required command fields:

- Internal payment/refund ID and a stable provider idempotency key.
- Server-authoritative amount in minor units and ISO currency.
- Checkout/order/cancellation/return correlation IDs needed for lookup and
  reconciliation.
- Capture mode and declared funds-flow mode.
- Business recipient IDs as internal opaque identifiers. Mapping to a provider
  connected account is Payment-owned configuration, never browser input.
- A bounded return URL only when the provider action requires a redirect. It
  must be selected from server configuration, not accepted as an arbitrary URL.

Normalized payment result:

```text
providerCode
providerPaymentReference
status = REQUIRES_ACTION | PROCESSING | SUCCEEDED | FAILED
nextAction = NONE | REDIRECT | CLIENT_CONFIRMATION
browserActionToken?       // ephemeral capability, never logged
safeFailureCode?          // allow-listed internal category
providerObservedAt
```

Normalized refund result:

```text
providerCode
providerRefundReference
status = PROCESSING | SUCCEEDED | FAILED
safeFailureCode?
providerObservedAt
```

Normalized verified event:

```text
providerCode
providerEventId
eventKind = PAYMENT_PROCESSING | PAYMENT_SUCCEEDED | PAYMENT_FAILED |
            PAYMENT_ACTION_REQUIRED |
            REFUND_PROCESSING | REFUND_SUCCEEDED | REFUND_FAILED
providerObjectReference
amountMinor?
currency?
providerCreatedAt
payloadSha256
```

Contract rules:

1. The adapter verifies authenticity before parsing an event into a trusted
   result. Unverified provider JSON is never a domain command.
2. Provider metadata is correlation help only. Payment retrieves and compares
   its own persisted amount, currency, checkout, order, and refund authority.
3. `createPayment` and `createRefund` are retry-safe through stable internal
   idempotency keys forwarded to the provider.
4. An ambiguous timeout is `PROCESSING`/unknown, not `FAILED`. Reconciliation
   retrieves by the known provider reference or idempotency correlation before
   any new create call.
5. Only safe, allow-listed failure categories leave the adapter. Raw provider
   messages are restricted to protected diagnostic telemetry and must not be
   returned to buyers or sellers.
6. Browser action tokens are returned only to the authenticated buyer for the
   matching checkout, are never placed in URLs, logs, events, notifications, or
   durable generic action-reference columns, and expire according to provider
   behavior.
7. A provider adapter does not confirm an order, restore inventory, decide a
   return disposition, calculate a refund, or publish a notification.

## 5. Internal state mapping

### Stripe payment mapping

| Stripe PaymentIntent status | Internal state/action | Order event | Buyer action |
|---|---|---|---|
| `requires_payment_method` | `REQUIRES_ACTION` + `CLIENT_CONFIRMATION`; record failed attempt with safe code `PAYMENT_METHOD_REQUIRED`, but do not terminally fail the reusable intent | none | choose/confirm another provider-hosted payment method |
| `requires_confirmation` | `REQUIRES_ACTION` + `CLIENT_CONFIRMATION` | none | confirm through provider UI |
| `requires_action` | `REQUIRES_ACTION` + normalized provider next action | none | complete provider-hosted redirect/3DS/action |
| `processing` | `PROCESSING` | none | none; show processing and wait |
| `succeeded` | `SUCCEEDED` | existing `payment.succeeded`, once | none |
| `canceled` | `FAILED` with safe code `PROVIDER_CANCELED` | existing `payment.failed`, once | none; checkout may show safe failure |
| any unknown value | reject/quarantine as unsupported; do not advance state | none | none until reconciled/operationally resolved |

Subscribe at minimum to `payment_intent.succeeded`,
`payment_intent.payment_failed`, `payment_intent.processing`, and
`payment_intent.canceled`. Stripe's `payment_intent.payment_failed` describes a
failed attempt and commonly leaves the reusable intent in
`requires_payment_method`; normalize it to `PAYMENT_ACTION_REQUIRED`, not a
terminal internal `payment.failed`. Only a truly terminal provider state or an
existing platform timeout/abandonment policy may emit terminal
`payment.failed`. Webhook order must not be assumed. Existing legal transition
checks remain authoritative; late or regressive events are persisted with an
ignored/rejected outcome.

### Stripe refund mapping

| Stripe Refund status/event | Internal refund state | Order event | Buyer action |
|---|---|---|---|
| `pending` | `PROCESSING` | none | none; UI remains processing |
| `requires_action` | `PROCESSING`; queue operational action | none | no in-app action in this milestone |
| `succeeded` | `SUCCEEDED` | existing final `payment.refunded`, once | none |
| `failed` or `canceled` | `FAILED`; record safe reason and queue operational recovery | no new business event in this milestone | none; support/operations resolves safely |
| any unknown value/event | reject/quarantine; no state advance | none | none |

The provider's successful HTTP response means the refund request was accepted,
not necessarily that the refund completed. This distinction is mandatory even
for card refunds that usually return `succeeded` immediately.

## 6. Refund compatibility with cancellation and returns

No refund caller may pass amount or currency. Existing derivation remains:

- Pre-fulfilment cancellation: Payment locks the succeeded intent and derives
  the full original amount/currency.
- Post-delivery group return: Payment validates the immutable group subtotal,
  currency, and cumulative refunded amount before provider submission.

The current Payment refund tables accept only `SUCCEEDED`, and cancellation
currently records an immediate success. A forward migration is therefore a
required implementation prerequisite, not an optional cleanup:

- Permit `PROCESSING`, `SUCCEEDED`, and `FAILED` in Payment-owned refund records
  and attempts; make completion/failure timestamps conditional on terminal
  state.
- Store a unique provider refund reference as soon as it is known.
- Keep the existing unique internal request/idempotency constraints.
- Publish existing `payment.refunded` only on final `SUCCEEDED`.
- Persist failed/ambiguous outcomes for reconciliation and operations; do not
  send a buyer “refund completed” notification before final success.
- Extend the cancellation compensation record with a forward migration so a
  provider-processing refund remains non-terminal. The return workflow already
  has `PENDING`/`PROCESSING`/`SUCCEEDED`; wire it to final Payment outcome rather
  than treating submission as completion.

This is a truthful extension of compensation progress, not a redesign of the
cancellation or return business state machines. Inventory restoration and the
seller-facing return disposition remain Order-owned and independent of provider
status. A refund failure must not silently re-stock or reverse a disposition.

## 7. Webhook endpoint and verification

Proposed public endpoint:

```text
POST /api/v1/webhooks/payments/{providerCode}
```

Requirements:

- Gateway routes only this exact path to Payment Service. It has no user-session
  authentication and no broad `/api/payment/**` exposure.
- Resolve an enabled adapter from an allow-listed provider registry. Unknown or
  disabled provider codes return `404` without parsing the body.
- Preserve the exact raw request bytes and required signature headers. Enforce a
  small configured body-size limit before parsing.
- Verify signature and timestamp tolerance before JSON deserialization into a
  trusted event. For Stripe, use the official verifier against `Stripe-Signature`
  and the endpoint-specific sandbox signing secret.
- Deduplicate on `(provider_code, provider_event_id)` and retain payload hash.
  A repeated ID with a different payload hash is a security conflict.
- Persist the verified event and its application outcome. Apply a legal state
  transition and write its outbox record atomically.
- Return `2xx` only after durable acceptance. Keep processing bounded; retryable
  persistence failures return non-2xx so the provider retries.
- Treat delivery as at-least-once and unordered. Unknown references are retained
  for reconciliation without creating an intent or order.
- Log only internal IDs, provider code, provider event/reference suffix or hash,
  normalized kind, outcome, and correlation ID. Never log raw bodies, signature
  headers, client secrets, API keys, customer payment data, or provider objects.

## 8. Idempotency and reconciliation

Use two layers:

1. Existing internal idempotency remains authoritative for caller replay and
   request-hash conflict detection.
2. Derive a stable provider key from operation type plus internal aggregate ID,
   for example `pay:create:<paymentIntentId>` and
   `pay:refund:<refundId>`. Forward the same key on every retry.

Do not include raw checkout content, PII, or secrets in provider idempotency keys.

| Boundary | Deterministic key/constraint |
|---|---|
| Browser retry/double-click | existing `checkout-payment:{checkoutId}` request key, reused for the same immutable request |
| Internal payment creation | existing caller-scope + request-key uniqueness, request hash, and one Payment intent per checkout |
| Provider payment creation | `payment:create:<internalPaymentIntentId>:v1`, reused for every timeout/retry |
| Cancellation refund | one internal refund per cancellation request plus `payment:refund:<internalRefundId>:v1` at provider |
| Return refund | one internal refund per return/group request plus `payment:refund:<internalRefundId>:v1` at provider |
| Provider event | unique `(providerCode, providerEventId)` plus immutable payload hash |

Conceptual proof for payment: the same checkout produces the same browser key;
the Payment idempotency row and unique checkout constraint select one internal
intent; that intent produces one stable provider key; provider idempotency and
retrieval resolve retries to one provider payment.

Conceptual proof for refund: the same cancellation or return compensation hits
its existing unique business-request constraint and selects one internal refund;
that refund produces one stable provider key; provider idempotency and retrieval
resolve retries to one provider refund. A key reused with a different canonical
request hash is a conflict, never a second operation.

Reconciliation extends `PAY-03`:

- Select non-terminal or ambiguous attempts whose next-check time has elapsed.
- Retrieve the provider object by stored provider reference. If creation timed
  out before a reference was stored, use the provider idempotency correlation or
  protected provider search capability; never issue a create with a new key.
- Normalize the retrieved state through the same transition function used by
  verified webhooks.
- Re-check immutable amount/currency and internal correlation before applying a
  terminal result.
- Apply transition plus outbox atomically and idempotently.
- Use bounded exponential backoff and terminal operational review after a
  configured attempt/age threshold.
- Alert on unknown provider references, signature failures, payload-hash
  conflicts, amount/currency mismatches, illegal transitions, exhausted
  reconciliation, and refunds stuck in processing.

## 9. Configuration and secrets

Add provider selection only in the implementation milestone:

```text
PAYMENT_PROVIDER=FAKE_LOCAL_DEMO_V1          # safe default
PAYMENT_EXTERNAL_PROVIDER_ENABLED=false      # independent kill switch
PAYMENT_STRIPE_SANDBOX_ENABLED=false         # adapter-specific gate
PAYMENT_STRIPE_SECRET_KEY=<secret reference>
PAYMENT_STRIPE_PUBLISHABLE_KEY=<public config>
PAYMENT_STRIPE_WEBHOOK_SECRET=<secret reference>
PAYMENT_STRIPE_API_VERSION=<pinned version>
PAYMENT_STRIPE_CONNECT_ENABLED=false         # later scope, separately gated
```

Rules:

- Startup fails closed if an external adapter is selected without its explicit
  enablement, sandbox credentials, webhook secret, and pinned API version.
- Sandbox and live credentials, endpoints, webhook secrets, and connected
  accounts are never interchangeable. The first milestone rejects live keys.
- Secrets come from the deployment secret mechanism or runtime environment,
  never Git, `.env` files, Compose defaults, Angular configuration, logs, test
  fixtures, or CI artifacts.
- Publishable key is the only provider credential permitted in the browser.
- Rotate API and webhook secrets independently. Support an overlap window for
  webhook-secret rotation if the provider supports multiple signatures.
- Keep fake provider, fake completion endpoint, and fake webhook secret disabled
  outside explicitly named local-demo profiles.

## 10. Frontend payment handling

The Angular checkout remains Order-facing. It must not call Payment Service
directly or determine payment success.

For Stripe sandbox:

1. Order creates/replays the internal Payment intent and returns the normalized
   action plus the public provider configuration needed for that checkout.
2. Angular loads Stripe.js from Stripe's documented origin and mounts Payment
   Element. Card number, expiry, and CVC are entered only in provider-controlled
   fields and sent directly to Stripe.
3. Angular uses the ephemeral client secret only to perform the required client
   confirmation/action. It does not persist or log it.
4. Redirect/3DS return only resumes the checkout view. The browser polls the
   existing confirmed-order result; it never marks payment or order successful.
5. Errors shown to the buyer use normalized safe categories and provider UI
   guidance. Raw decline details and internal references are not exposed.

The local-demo button remains available only under the existing explicit demo
feature gate. External-provider UI is hidden unless the server advertises the
selected enabled sandbox provider.

## 11. CI, sandbox, and failure testing

Default CI remains deterministic and credential-free with
`FAKE_LOCAL_DEMO_V1`. Add adapter contract tests that every provider must pass:

- stable idempotency forwarding and replay behavior;
- amount/currency and correlation mapping;
- payment/refund status normalization, including unknown status rejection;
- safe error mapping and log redaction;
- signature verification before parsing;
- duplicate, altered-duplicate, stale-signature, malformed, oversized, unknown-
  reference, unordered, and late-terminal webhook cases;
- create timeout before/after provider acceptance and retrieval reconciliation;
- full and partial refund, pending refund, failed refund, and cumulative-refund
  bound enforcement;
- outbox atomicity, retry/backoff, and exactly-once business effect.

Add a separate opt-in sandbox workflow protected by repository secrets. It must
not run on untrusted fork pull requests and must use provider test values only.
Required sandbox cases: success, decline, 3DS challenge, duplicate webhook,
out-of-order webhook, delayed completion, full refund, partial group refund,
refund failure/pending simulation where supported, webhook replay, and recovery
after Payment/Order restart.

Provider event fixtures may be committed only when scrubbed and synthetic. Never
record real dashboard payloads or headers without sanitization.

## 12. Observability and runbook requirements

Metrics:

- create/refund calls by provider, operation, normalized result, and latency;
- webhook received/verified/rejected/deduplicated/applied/ignored counts;
- webhook apply latency and provider delivery age;
- reconciliation backlog, oldest age, retries, recoveries, and terminal items;
- payment/refund counts by normalized state;
- outbox pending/retry/terminal counts and oldest age.

Alerts must cover sustained signature failures, nonzero payload conflicts,
amount/currency mismatches, rising webhook failure rate, reconciliation or
outbox backlog beyond threshold, and processing refunds beyond the provider-
specific service window.

The runbook must include provider/dashboard lookup using safe hashed references,
webhook redelivery, reconciliation replay, key and webhook-secret rotation,
adapter kill-switch activation, sandbox reset, stuck refund handling, and the
rule that operators never manually mark an Order paid without a verified Payment
record and audited recovery procedure.

## 13. Security and data-handling rules

- The platform never accepts, stores, proxies, or logs raw PAN, CVC, magnetic-
  stripe data, or provider authentication credentials.
- TLS is mandatory for browser checkout and webhook endpoints outside local
  development.
- Provider browser components must be loaded according to provider guidance;
  Content Security Policy changes are narrow and documented.
- Client secrets/action tokens are treated as buyer-scoped ephemeral
  capabilities, not general API credentials.
- Webhook verification uses the raw body and constant-time/provider-library
  verification with a nonzero timestamp tolerance.
- Amount, currency, merchant/funds-flow, business recipient, capture mode, and
  return URL are selected or revalidated server-side.
- Provider metadata contains opaque internal IDs only; no unnecessary buyer,
  seller, address, token, authorization, or payment-instrument data.
- Payment APIs and events expose only normalized safe fields. Notifications never
  contain provider error text or identifiers.
- Access to provider dashboards, secrets, refunds, and redelivery is least-
  privilege and auditable.

## 14. Risks and deferred decisions

1. **Merchant-of-record and marketplace liability:** the current command says
   platform merchant of record and separate charge/transfer. Stripe Connect can
   implement that shape, but legal, tax, dispute, negative-balance, fee, and
   seller-onboarding decisions are not approved here.
2. **Asynchronous refunds:** current local-demo persistence overstates the
   synchronous case. The forward migrations and final-success event handling
   described above are required before calling the sandbox slice complete.
3. **API/event version drift:** pin provider API and webhook versions, test
   upgrades, and reject unknown states rather than guessing.
4. **Browser capability leakage:** a client secret is intentionally usable by
   the matching buyer. It requires strict authorization, TLS, redaction, and no
   URL or durable generic-event exposure.
5. **Webhook reachability:** local development needs a documented secure tunnel
   or provider CLI workflow. This must not broaden gateway exposure.
6. **Provider outage/ambiguous response:** idempotency and reconciliation are
   mandatory before retries. Checkout must show processing, not solicit another
   charge.
7. **Payment-method scope:** first implementation is sandbox card only. Wallets,
   bank debits, delayed methods, saved methods, disputes, payouts, transfers,
   and live mode remain deferred and separately gated.
8. **Provider portability:** the adapter normalizes lifecycle and security, not
   every provider feature. Provider-specific capabilities remain behind feature
   checks and must not leak into Order domain contracts.

## 15. Exact next implementation milestone after approval

Milestone: **V2 Payment External Sandbox Adapter — Stripe, card-only**.

Implementation boundary:

1. Add provider registry/selection and the frozen adapter types inside Payment
   Service; retain fake as default.
2. Add Stripe sandbox adapter and official Java SDK with pinned version.
3. Add forward-only Payment refund/status migrations and the minimal compatible
   cancellation-processing migration needed to represent provider processing;
   do not alter amount authority or business terminal meanings.
4. Generalize verified webhook routing/parser application for provider-specific
   verification and normalized events; add the exact Gateway webhook route.
5. Implement idempotent create/retrieve/refund/retrieve-refund and reconciliation
   workers with bounded retry.
6. Add Angular Payment Element integration behind the external-provider feature
   gate, with no raw card handling and no browser-authoritative success.
7. Wire final refund success to the existing `payment.refunded` business event
   and preserve cancellation/return/notification ownership.
8. Add contract, MySQL concurrency, webhook, recovery, frontend, and opt-in
   sandbox tests plus runbook/metrics.

Acceptance boundary: one sandbox card purchase can require 3DS, complete through
a verified webhook, confirm exactly one order, complete one full cancellation
refund and one partial group return refund only after verified final provider
state, recover from duplicate/out-of-order delivery and restart, and leave all
fake-provider RC workflows green. Live keys, production traffic, seller payouts,
Connect onboarding, disputes, and additional payment methods are explicitly out
of scope.

**Stop condition:** do not begin this milestone until the commerce owner replies
with explicit approval of Stripe sandbox (or names a different candidate) and
accepts the asynchronous refund compatibility work in the milestone boundary.

## Official provider references

Stripe:

- [PaymentIntents lifecycle and server webhook authority](https://docs.stripe.com/payments/payment-intents)
- [Webhook verification, replay protection, duplicates, ordering, and retries](https://docs.stripe.com/webhooks)
- [Payment Element tokenization](https://docs.stripe.com/payments/elements)
- [Refund lifecycle](https://docs.stripe.com/refunds)
- [Testing and 3DS test values](https://docs.stripe.com/testing)
- [Java development environment](https://docs.stripe.com/get-started/development-environment?lang=java)
- [Connect separate charges and transfers](https://docs.stripe.com/connect/separate-charges-and-transfers)

Adyen:

- [Refunds and asynchronous refund outcomes](https://docs.adyen.com/online-payments/refund)
- [API idempotency](https://docs.adyen.com/development-resources/api-idempotency)
- [Webhook operations](https://docs.adyen.com/development-resources/webhooks)
- [Webhook security](https://docs.adyen.com/development-resources/webhooks/secure-webhooks)
- [Adyen for Platforms](https://docs.adyen.com/platforms)
- [Split transactions](https://docs.adyen.com/platforms/online-payments/split-transactions)

PayPal:

- [REST API idempotency](https://developer.paypal.com/reference/guidelines/idempotency/)
- [Webhook delivery and verification](https://developer.paypal.com/api/rest/webhooks)
- [Multiparty payment solutions](https://developer.paypal.com/docs/multiparty/)
- [Multiparty Checkout integration](https://developer.paypal.com/platforms/checkout)
- [JavaScript SDK](https://developer.paypal.com/sdk/js/)
