# V2-ORD-02C Business Fulfillment Queue And Detail UI

Status: implemented and source-verified on 2026-07-20.

Requirements: `ORD-03 View business orders`.

Depends on:

- `V2-ORD-02B` business fulfillment queue/detail API;
- Auth-owned `ORDER_VIEW` and `ORDER_FINANCE_VIEW` membership permissions; and
- the immutable Order Service business-group snapshots.

## 1. Delivered Surface

The Angular business seller portal provides read-only routes:

```text
/seller/orders
/seller/orders/{businessOrderId}
```

The queue uses the active store context, an optional exact ORD-02B status
filter, a bounded page size of 20, and the server's opaque cursor unchanged.
It includes loading, empty, dependency-error, next-page, and previous-page
states. The detail renders only the approved business-group item, policy,
payment-status, total, and minimum shipping-address snapshots.

Owners and managers enter through the same store context. The UI requires
`ORDER_VIEW` before making an Order request. `platformFeeProjection` is
rendered only when the API supplies it, so manager responses do not expose or
infer finance data. Backend authorization remains authoritative.

There are no acceptance, shipment, cancellation, refund, payment, inventory,
or other mutation controls. Provider references, buyer identity keys,
event/outbox data, hashes, leases, and service metadata are never modeled or
rendered.

## 2. Default-Off Boundary

Angular `features.businessOrders` defaults to `false` in production and local
development. While disabled:

- seller navigation has no Orders entry;
- both seller order routes redirect to the seller dashboard without loading
  the feature component; and
- the component's defensive capability check performs zero store-context and
  Order requests if instantiated directly.

Gateway `msb.gateway.features.business-orders` also defaults to `false`.
The disabled gateway owns
`/api/v1/businesses/*/orders` and
`/api/v1/businesses/*/orders/**` locally with deterministic `404` responses.
Authentication still runs before that route.

When explicitly enabled, the gateway follows the existing authenticated BFF
pattern: token relay, correlation propagation, Order circuit breaker, and
removal of browser-supplied `X-User-Id`, `X-Actor-User-Id`,
`X-Keycloak-Sub`, and `X-Roles` headers.

Frontend and gateway gates must be enabled together only in a controlled
release. This slice does not activate either gate in runtime configuration.

## 3. Verification

Focused source verification covers:

- GET-only typed service requests, bounded filters, and opaque cursors;
- default-off route redirects, absent navigation, and network silence;
- owner/manager view access and missing-permission silence;
- loading, empty, outage, retry, cursor, and status-filter states;
- immutable item/address/policy rendering;
- finance presence/absence behavior and hidden-field redaction;
- semantic table, labels, live regions, and responsive layout hooks;
- gateway disabled/enabled registration, authentication, forwarding,
  correlation/token relay, and spoofed identity-header removal.

No Flyway migration is required. No backend contract or persisted state
changes.

## 4. Completion Boundary

`V2-ORD-02C` completes the third business slice and advances the lane from
`2/3` to `3/3`. The business lane stops here. Mandatory ORD-02 business-only
cleanup must run before `V2-SHP-01A` or any other business successor.

Browser and VM verification remain deferred until the source reaches the
approved GitHub/VM release path. No runtime activation or deployment occurred
in this slice.
