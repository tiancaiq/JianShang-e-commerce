# ADM-LIST-06 — Reversible Listing Suspension and Reinstatement

Status: complete.

## Purpose

ADM-LIST-06 gives authorized platform staff reversible listing controls that remain separate from moderation resolution, permanent administrative removal, seller/business enforcement, and existing orders.

## Staff walkthrough

1. Sign in to the admin portal with `admin.listing.moderation.read` and open a listing review detail.
2. In **Listing enforcement**, inspect the current visibility and purchase boundary pills, active actions, history, and audit timeline.
3. Choose **Restrict selected capability** for one or both scopes, or **Suspend listing capabilities** to select both scopes deterministically.
4. Enter a stable uppercase reason code and staff reason. Set an expiry, or explicitly acknowledge indefinite enforcement.
5. Select **Preview impact**. Verify the predicted visibility/purchasability state and the no-cascade statement.
6. Confirm the action. The listing moderation status and lifecycle remain unchanged.
7. For an active action, select **Preview reinstatement**, provide a reason, inspect remaining overlapping controls, and confirm.

Administratively removed listings are read-only in this workbench. Auditors can inspect the ledger and timeline without mutation controls.

## Runtime boundaries

- `LISTING_PUBLIC_VISIBILITY` immediately removes the listing from public detail, browse/search, liked results, public media, hybrid rehydration, and index/backfill sources. Seller, admin, and internal inspection remain available.
- `LISTING_PURCHASABILITY` returns a stable `403` before inventory reservation, payment-intent creation, or order-confirmation inventory commit.
- Product decision unavailability returns `503`; Order does not continue optimistically.
- Existing paid or created orders, listing content, moderation history, seller/business state, inventory quantity, and payouts are not mutated.

## Enforcement semantics

- Moderation decides listing lifecycle and publication state. Enforcement is a separate, reversible policy ledger and never changes the listing lifecycle version or moderation decision.
- `REMOVED_BY_ADMIN` remains the authoritative administrative-removal state. It cannot be suspended or reinstated through this workbench, and revoking historical enforcement never republishes it.
- `RESTRICT` preserves the selected operational scopes. `SUSPEND` deterministically expands to both `LISTING_PUBLIC_VISIBILITY` and `LISTING_PURCHASABILITY`. Listing-level `BAN` is rejected because permanent removal already has its own lifecycle workflow.
- Dry runs perform the same authorization, lifecycle, scope, and version validation as confirmation without creating actions, events, or idempotency records.
- Effective restrictions are the union of all active, effective, unexpired actions. Revocation targets one action and recalculates the remaining union; expiry stops an action from affecting runtime without deleting its history.
- Create and revoke commands require optimistic versions and idempotency keys. A replay with the same fingerprint returns the original result; reuse with a different command conflicts.
- Apply, revoke, expiry, and overlap do not mutate the seller, user, business, inventory quantity, existing orders, payouts, or unrelated enforcement records.

## Composition and inspection

- User, business, and listing enforcement are evaluated independently and compose at their respective runtime boundaries. Restoring a listing does not restore a restricted user or business.
- Seller and authorized admin inspection remains available while public visibility is blocked. Public direct detail, public media, normal search/browse, derived search indexing, and hybrid result revalidation all enforce the visibility decision.
- Checkout, payment-intent creation, and paid-order confirmation recheck purchasability before their irreversible side effects. Dependency failure is closed with `503`; a known restriction is `403` with `LISTING_PURCHASABILITY_RESTRICTED`.
- The admin view exposes current effective boundaries, active actions, immutable action history, and normalized creation/revocation audit events with correlation metadata.

## Deferred behavior

Marketplace report submission, report inbox/case investigation, appeals, notifications, and AI-assisted enforcement are deferred to later approved milestones. This slice does not add cascade enforcement, automated moderation decisions, payout controls, or changes to existing-order fulfillment.

## Release verification

- Product policy/service tests: restrict scope preservation, suspension expansion, ban rejection, removed-listing rejection, idempotency, stale versions, overlap/revocation, and permissions.
- Product MySQL integration: apply each scope, verify every public surface and seller/admin inspection, revoke, and verify restoration without lifecycle changes.
- Order tests: known restriction `403`, decision unavailability `503`, and checks before reservation/payment/confirmation side effects.
- Auth MySQL migration: permissions active with existing role grants preserved.
- Angular tests: centralized capability logic, suspension scope display, preview invalidation, and removed/auditor read-only states.
- Playwright: super-admin apply/reinstate, limited-role forbidden commands, auditor read-only, and separate visibility-only/purchasability-only/combined runtime workflows.

## Current verification record

- Angular production build and complete test suite: passed (`633/633`).
- Focused Angular listing-admin/enforcement tests: passed (`16/16`).
- Product enforcement policy/service/MySQL tests: passed (`18/18`), including public
  detail, browse, ID revalidation, search-index, hybrid revalidation, expiration,
  overlapping actions, and `REMOVED_BY_ADMIN` precedence.
- Focused Order payment/confirmation/wiring tests: passed (`24/24`), including
  denial before payment-intent creation and order-confirmation inventory commit.
- Existing admin release-candidate Playwright workflow: passed (`2/2`).
- Listing enforcement Playwright workflows: passed (`5/5`) for full temporary
  suspension/reinstatement, auditor read-only/direct-command denial,
  purchasability-only checkout denial and restoration, business new-sales
  composition, and permanent administrative-removal precedence after revocation.
- Complete Chromium Playwright suite: passed (`13/13`), including existing admin,
  user enforcement, trade completion, and business enforcement workflows. The
  listing-enforcement scenarios use a dedicated fixed listing so permanent removal
  cannot mutate the legacy moderation fixture used by the release-candidate tests.
- Complete 13-module Maven reactor: passed (`BUILD SUCCESS`).
- The implementation and runtime acceptance workflows are complete. The prior
  repository-wide whitespace blocker in
  `api-gateway/.../AgentDiscoveryRouteIntegrationTests.java` has been corrected
  without changing test behavior, and `git diff --check` passes. The complete
  post-cleanup matrix passed: the 13-module Maven reactor and package gate, 633
  Angular tests, the production Angular build, 13 Chromium Playwright scenarios,
  and the final 2-scenario admin release-candidate rerun. The five browser
  workflows prove reversible listing enforcement without
  restoring a separate business restriction or a listing in `REMOVED_BY_ADMIN`
  lifecycle state.
