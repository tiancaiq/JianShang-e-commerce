# V2 Commerce Local Runtime

This is the authoritative local/demo runbook for `V2-COM-RC-01`. The supported
runtime is the base demo composition plus `docker-compose.cart-runtime.yml`;
the overlay is mandatory. Base Compose alone is not a commerce runtime.

## Prerequisites

- Java 21 and the repository Maven wrapper
- Node.js 20 or newer
- Docker Desktop with Compose v2
- PowerShell 7 (Windows PowerShell is also supported locally)

Supply these values at runtime; never commit or print them:

- `KEYCLOAK_ADMIN_PASSWORD`
- `KEYCLOAK_MARKETPLACE_CLIENT_SECRET`
- `COMMERCE_INTERNAL_SERVICE_TOKEN`
- `PAYMENT_INTERNAL_SERVICE_TOKEN`
- `LOCAL_DEMO_BUYER_PASSWORD`
- `LOCAL_DEMO_BUYER_B_PASSWORD`
- `LOCAL_DEMO_HARBOR_PASSWORD`
- `LOCAL_DEMO_SHEN_PASSWORD`

## Bootstrap and verification

From the repository root:

```powershell
.\tools\start-commerce-demo.ps1
.\scripts\verify-commerce-runtime.ps1
.\tools\run-commerce-rc.ps1
```

`start-commerce-demo.ps1` packages the services, starts the approved two-file
composition, verifies the compiled frontend and service capabilities, and then
prepares deterministic fixtures. To prepare fixtures again without rebuilding:

```powershell
.\tools\prepare-commerce-demo.ps1
```

The fixture command is idempotent and local-demo only. It maintains stable
businesses, seller memberships, listings, buyers, addresses, and known stock.
It refuses to reset a listing with outstanding reservations. Reusable fixtures
are reset; preserved evidence orders are neither deleted nor rewritten.

Additional engineering gates:

```powershell
.\tools\verify-commerce-clean-migrations.ps1
.\tools\verify-commerce-concurrency.ps1
.\tools\check-commerce-invariants.ps1
.\tools\check-commerce-health.ps1
.\tools\trace-commerce-flow.ps1 -OrderId <ORDER_ULID>
```

The trace command prints only safe IDs, states, amounts, and attempt counts. It
does not print addresses, credentials, provider payloads, or outbox payloads.

## URLs

| Surface | URL |
| --- | --- |
| Marketplace | `http://localhost:4200/marketplace` |
| Buyer cart | `http://localhost:4200/cart` |
| Buyer orders | `http://localhost:4200/account/orders` |
| Buyer notifications | `http://localhost:4200/account/notifications` |
| Seller portal/orders | `http://localhost:4200/seller/orders` |
| Seller notifications | `http://localhost:4200/seller/notifications` |
| Gateway | `http://localhost:9000` |
| Keycloak | `http://localhost:8181` |

Deep links require authentication and remain authorization scoped.

## Demo usernames

Passwords are runtime supplied only.

- Buyer A: `trade.buyer`
- Buyer B / unauthorized authenticated actor: `trade.seller`
- Harbor seller owner: `harbor.seller`
- Shen seller owner: `shen.seller`

## Capability matrix

| State | Capability |
| --- | --- |
| SUPPORTED LOCAL DEMO | Business catalog, cart, checkout, inventory reservation/commit, deterministic demo payment, multi-business orders |
| SUPPORTED LOCAL DEMO | Independent seller acceptance, processing, demo shipment/delivery |
| SUPPORTED LOCAL DEMO | Pre-acceptance whole-order cancellation, exact demo refund/restock |
| SUPPORTED LOCAL DEMO | Event-driven buyer/seller notifications with replay deduplication |
| SUPPORTED LOCAL DEMO | One whole-business-group post-delivery return, disposition, and exact demo refund |
| DEFAULT OFF | All commerce routes and UI when the approved overlay/profile is absent; preflight rejects this as an RC runtime |
| NOT IMPLEMENTED | Stripe/real payments, real settlement/refunds, carriers/labels, payouts |
| NOT IMPLEMENTED | Partial line returns, exchanges, reviews, recommendations, promotions, email/SMS/push, WebSockets, advanced analytics |

## Topology and drift protection

The commerce overlay pins MySQL 8.4 and Redis 7.4, selects the Angular
`demo-checkout` build, marks every participating container with
`COMMERCE_RUNTIME_PROFILE=V2_COMMERCE_RC_01`, enables the bounded commerce
flags, and supplies service-to-service URLs. `verify-commerce-runtime.ps1`
checks actual container configuration, the compiled frontend marker, public
route capabilities, service health, MySQL version, Redis PING, and Keycloak.
It therefore fails after an accidental recreation from base Compose instead of
silently accepting a marketplace-only application.

The `.env` file remains developer-local and is not an authoritative source of
feature state. CI supplies ephemeral values and invokes the same overlay and
verification scripts.
