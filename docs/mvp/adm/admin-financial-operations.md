# ADM-FIN-00/01/02 — Payment and Refund Administration

Status: implemented for the existing deterministic payment provider. This slice adds finance visibility and controlled refund execution; it does not add payouts, chargebacks, arbitrary payment editing, enforcement, or AI decisions. ADM-GOV now places independent approval in front of configured large refunds.

## Ownership and authorization

Payment Service owns payment/refund state, cumulative refund calculations, command idempotency, provider invocation, attempts, and finance timelines. Order Service supplies a bounded internal payment-to-order context projection; Auth Service supplies the authenticated admin decision and safe buyer/business labels. Services do not query each other's schemas.

The active permissions are:

| Permission | Super Admin | Platform Admin | Support | Auditor |
| --- | --- | --- | --- | --- |
| `admin.finance.read` | Yes | Yes | Yes | Yes |
| `admin.refund.read` | Yes | Yes | Yes | Yes |
| `admin.refund.execute` | Yes | Yes | No | No |

Backend permission checks are authoritative. Browser-supplied actor headers are removed at the gateway; the actor and permission set are resolved from the bearer token and Auth Service.

## Read models

`GET /api/v1/admin/payments` is a server-paginated payment queue. It supports exact identifier search plus payment, business, order, status, refund-status, provider, date, amount, page, size, and allow-listed sort filters. Rows distinguish original amount, captured amount, successful refunds, pending refunds, current refundable amount, and safe reconciliation state.

`GET /api/v1/admin/payments/{paymentId}` adds purchase-time order items, businesses, safe identity labels, related refunds, dispute refund recommendations, normalized history, and server-derived capabilities. Raw payment credentials, card data, bank data, tokens, exact addresses, and unnecessary PII are never returned.

`GET /api/v1/admin/refunds` and `GET /api/v1/admin/refunds/{refundId}` expose cancellation, business-return, and human-admin refunds in one paginated audit view. Detail includes the amount before and after the operation, attempt information, safe provider reference/failure fields, reconciliation state, and history.

## Controlled refund command

Both full and partial refunds use the existing Payment-owned refund/provider domain:

1. `POST /api/v1/admin/payments/{paymentId}/refund/dry-run` validates the current payment version, currency, remaining refundable amount, Order context, and optional dispute recommendation. It performs no write and does not call the provider.
2. The UI displays captured, already refunded, requested, projected refunded, and projected remaining amounts and requires explicit confirmation.
3. `POST /api/v1/admin/payments/{paymentId}/refund` requires `admin.refund.execute`, an actor-scoped `Idempotency-Key`, and the previewed payment version.
4. Payment evaluates the Auth-owned `LARGE_REFUND` policy. Below-threshold requests continue immediately; above-threshold requests return HTTP `202` with an approval reference and perform no provider call or refund write.
5. After independent approval, Auth revalidates current Payment state and dispatches to Payment's typed internal governed-refund endpoint. Payment Service locks the payment row, recalculates successful and in-flight refund totals across cancellation, business-return, and admin refund sources, and re-runs the preview before invoking the provider.
6. The refund, attempt, status history, outbox event (on success), idempotency command, and payment version change are recorded transactionally.

A full refund means the current remaining refundable amount, not the original captured amount. Partial refunds must be positive, use the payment currency, and remain within the current boundary. A dispute-linked operation must reference a recommendation attached to the same Order payment and match its amount and currency exactly. The dispute decision remains immutable historical evidence; successful financial execution links back to the dispute and order.

## Safety and lifecycle semantics

The cumulative invariant is `successful refunds + reserved in-flight refunds <= captured amount`. The same calculation protects legacy order cancellation, business-return, and admin commands. Optimistic version checks reject stale previews, while the payment row lock serializes concurrent execution. Actor-scoped request hashes make exact retries replay the original result and reject changed payloads.

The current fake provider returns a synchronous, confirmed `SUCCEEDED` result. The API only reports `SUCCEEDED` after that confirmed result has been persisted. Provider declines are stored as `FAILED`. Exceptions with an unknown provider outcome are stored with `REQUIRES_ATTENTION` and safe failure metadata. Automatic retry is intentionally unavailable because the provider adapter has no status lookup/reconciliation contract; the system never blindly duplicates an uncertain money operation. A future asynchronous provider may use `PENDING`/`PROCESSING`, but those states must reflect actual provider state and require a reconciliation adapter before retry can be enabled.

Order cancellation continues to use its existing compensation workflow. If any return or admin amount is already committed, a legacy full cancellation refund is rejected instead of exceeding captured funds. No endpoint edits payment amount, status, provider reference, or history directly.

## Governance integration and deferred work

The database policy seed requires one independent approval for refunds of at
least `1000.00 USD`; this is controlled policy configuration and must be
reviewed before production. The requester and reviewer must differ. Review
does not bypass `admin.refund.execute`, and execution retains all Payment locks,
versions, cumulative-ceiling checks, and idempotency. See
[admin-governance.md](admin-governance.md).

Seller payouts (`ADM-PAY-03`) remain deferred until payout architecture exists. Chargebacks, provider reconciliation jobs, safe retry for providers that support it, automated enforcement, analytics, and AI finance actions are outside ADM-FIN-00/01/02.

`ADM-SUP-00/01` can validate and link existing payments/refunds and hand a
payment issue to this console. It never receives `admin.refund.execute`
implicitly and exposes no refund action in Support. See
[admin-support-operations.md](admin-support-operations.md).

ADM-SYS projects `REQUIRES_ATTENTION` refund reconciliation safely into the
Operations console. It intentionally provides no reconciliation retry: the
current provider adapter still lacks an authoritative status lookup. Payment
state remains read-only until that owner-service contract exists. See
[admin-system-operations.md](admin-system-operations.md).
