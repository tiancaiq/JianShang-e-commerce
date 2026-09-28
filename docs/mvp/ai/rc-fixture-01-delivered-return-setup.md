# RC-FIXTURE-01 — delivered return acceptance setup

This is disposable local acceptance data for the customer Agent RC. It uses the existing Keycloak, Auth, Product, Inventory, Order, and fake local Payment contracts. It does not submit a return or enable Agent checkout, cancellation, or return mutations.

## Prepare or reuse

From the repository root on the disposable demo stack:

```powershell
& tools/prepare-customer-agent-rc-fixture.ps1
```

The script requires three runtime-only Windows user or process environment variables: `LOCAL_DEMO_BUYER_PASSWORD`, `LOCAL_DEMO_BUYER_B_PASSWORD`, and `LOCAL_DEMO_HARBOR_PASSWORD`. It never prints their values. It reads the running Gateway's local Keycloak service-account and marketplace-client secrets, and Auth's internal fixture token, without persisting them. Do not put credentials in repository files or command-line arguments.

The two buyer-only Keycloak identities are `rc.customer.a@msb.local` and `rc.customer.b@msb.local`. The business seller is the existing disposable `harbor.seller@msb.local`. The Gateway's existing identity service account creates or resets only these named fixture identities. The existing local-only Auth cart fixture contract binds Harbor's owner membership to the current seller subject; it is service-token protected and false by default outside the cart runtime overlay.

The dedicated SKU `MSB-CUSTOMER-AGENT-RC-RETURN` belongs to Harbor Cart Supply and has the title `RC Delivered Return Fixture`. Product creation/media publication and Inventory initialization or stock correction use seller-authorized application APIs. The `docker-compose.customer-agent-rc-fixture.yml` overlay selects Product's approved `local-demo` metadata-only listing media adapter and builds a disposable frontend profile that exposes the existing Agent, Cart, Orders, and Returns pages together. It leaves `.env*` and normal S3 credentials untouched. The Product override is required while the disposable stack's configured S3 check returns `ACCESS_DENIED`.

The first successful run clears Customer A's disposable cart, adds one dedicated item, validates it, creates checkout, creates one payment intent, calls the existing customer-facing fake-demo payment completion, waits for the confirmed order, then uses the Harbor seller's normal accept → processing → shipment → demo-delivery APIs. It reads the buyer's exact group return endpoint and stops before any return submission.

The default rerun reuses the **latest** dedicated delivered order only when it remains return-eligible and has no return. If that latest generation has a return or an expired window, the script creates a new order generation. If the latest dedicated order is unfinished, it stops for recovery rather than silently creating another order. `-Fresh` explicitly creates another generation; use only when a new target is intentionally needed. Immutable order and payment history remains.

## Browser readiness, then full RC

Sign in separately as Customer A and Customer B at the local Marketplace UI. For each, open Marketplace Agent V2 and ask “What can you help me with?”; verify a provider-backed natural response. Customer A's Orders page must show the delivered fixture; Customer B's Orders page must not. The script independently checks Customer B receives a privacy-safe denial for A's exact order and return endpoints. Browser sessions must be verified after refresh; a restored prior Agent message alone is not proof of current authentication. The RC frontend profile enables existing customer UI routes; it does not change the independently disabled high-risk Agent capability flags.

Keep Agent checkout, order mutations, and return requests disabled until the separate full customer-Agent RC run. Do not submit the prepared return during fixture setup.

## Recovery and cleanup

- A failed or consumed return target can be replaced by rerunning the script after inspecting the latest dedicated order. Use `-Fresh` only for a deliberate additional generation.
- Disposable carts can be cleared through their normal customer API. Expired unused checkouts follow the existing expiry/release workflow; do not edit rows directly.
- Old dedicated listings may be paused through the seller Product API after acceptance, but immutable orders, payments, shipments, and any submitted returns must be retained.
- To restore Product's selected S3 adapter later, recreate only Product using the normal Compose files **without** the RC fixture overlay, after the external S3 access issue is resolved. Do not edit `.env*` or remove unrelated Docker volumes/networks.
- The three local user-level fixture password variables may be removed after acceptance with `[Environment]::SetEnvironmentVariable('NAME', $null, 'User')` for each exact fixture variable. This does not delete the disposable Keycloak identities.

This script does not use direct database writes to produce inventory, order, delivery, or return state. Read-only database queries are permitted for one-payment/one-order acceptance evidence.
