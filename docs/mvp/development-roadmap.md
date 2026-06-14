# MVP Development Roadmap

## 1. Delivery Rules

- Implement one numbered slice at a time.
- A slice must include migration, backend, frontend, authorization,
  observability, and tests where applicable.
- Do not start a dependent slice until its prerequisite acceptance tests pass.
- Keep feature flags for incomplete user-facing workflows.
- Payment and inventory slices require idempotency and failure tests before UI
  exposure.
- AI work starts only after the APIs it calls are stable.

## 2. Definition of Done for Every Slice

A slice is done when:

1. Requirement ID and acceptance criteria are linked in the change.
2. Database migration is forward-safe and reviewed.
3. API contract and OpenAPI definition agree.
4. Unit and integration tests pass.
5. Authorization and tenant-isolation tests pass.
6. Structured logs and key metrics exist.
7. User-visible errors are handled in the relevant application.
8. No secret, token, payment detail, or unnecessary PII is logged.
9. Documentation is updated if the implemented behavior changes the contract.

## 3. Phase 0: Foundation

### FND-01 Establish test baseline

- Run and repair existing module tests.
- Record current API behavior.
- Add root commands for backend and frontend verification.

### FND-02 Choose and document identity flow

- Remove ambiguity between session auth, custom JWT, and Keycloak.
- Document token validation and refresh behavior.
- No feature implementation in this slice.

### FND-03 Standardize API errors and correlation IDs

- Add standard error envelope.
- Generate/propagate correlation IDs.
- Add frontend error parsing.

### FND-04 Add migration and database conventions

- Confirm MySQL ID representation, UTC, charset, and Flyway rules.
- Create schema ownership map.

### FND-05 Add outbox and event envelope library

- Define reusable outbox record and publisher behavior.
- Define event envelope and consumer deduplication helper.

### FND-06 Establish Angular multi-app workspace plan

- Preserve existing marketplace behavior.
- Add marketplace, seller portal, and admin portal build targets.
- Add shared auth, API client, models, and UI libraries.

### FND-07 Add CI quality gates

- Backend compile/tests.
- Frontend lint/test/build.
- Migration validation.
- Dependency and secret scanning.

## 4. Phase 1: Identity and Accounts

Implement in order:

1. `IAM-01` registration and email verification.
2. `IAM-02` login, refresh, and logout.
3. `IAM-03` password recovery.
4. `IAM-04` profile view/edit.
5. `IAM-05` address book.
6. `IAM-06` role and tenant authorization.
7. Admin authentication hardening and `ADM-01`.

Exit criteria:

- Marketplace login works.
- Seller/admin applications can share authentication.
- Cross-user, cross-business, and admin access tests pass.

## 5. Phase 2: Seller Identities

### Individual seller

1. `IND-01` activation and public profile.

### Business onboarding

2. `BUS-01` application draft.
3. `BUS-02` submit application.
4. `BUS-03` verification callback.
5. Admin application queue read view.
6. `BUS-04` approve/reject/request information.
7. `BUS-05` store profile.
8. `BUS-06` versioned policies.
9. `BUS-07` staff invitations and roles.

Exit criteria:

- Individual seller can be activated from marketplace account.
- Approved business owner can enter seller portal.
- Staff are constrained to their business and assigned permissions.

## 6. Phase 3: Listings and Media

Implement small slices:

1. `LST-01` category read API and marketplace category UI.
2. Admin category seed/management tooling required for test data.
3. `LST-02` signed upload request.
4. `LST-03` upload confirmation and scan state.
5. `LST-04` individual listing draft API.
6. Individual listing create form.
7. `LST-05` business listing draft API.
8. Business listing create form.
9. `LST-06` listing edit with optimistic locking.
10. `LST-07` attach, remove, and order images.
11. `LST-08` submit for review.
12. `ADM-02` moderation queue claim.
13. `LST-09` listing decision and notification.
14. `LST-10` pause, close, and relist.
15. `LST-11` public listing detail and seller-type disclosure.

Exit criteria:

- Both seller types can create moderated listings.
- Only active approved listings are public.
- Individual and business listing behavior is visibly distinct.

## 7. Phase 4: Search and Storefront

1. Create listing event projector.
2. `SRC-01` activate/update/deactivate indexing.
3. `SRC-02` keyword search.
4. Add category filter.
5. Add seller-type filter.
6. Add condition and price filters.
7. Add approximate location filter for individual listings.
8. Add cursor pagination and result cards.
9. `SRC-03` public business storefront.
10. Add index rebuild and failed-index operations command.

Exit criteria:

- Search excludes unavailable listings.
- Index can be rebuilt.
- Search load test meets agreed baseline.

## 8. Phase 5: Individual Chat and Trades

1. `CHT-01` create/list conversations.
2. Conversation marketplace UI.
3. `CHT-02` HTTP message send/history.
4. Add realtime message delivery.
5. Add unread count/read state.
6. `CHT-03` block user.
7. `CHT-03` report conversation.
8. `OFF-01` submit offer.
9. Render offer card in chat.
10. `OFF-02` reject and withdraw.
11. `OFF-02` counteroffer.
12. `OFF-02` accept offer with concurrency protection.
13. `TRD-01` create trade and reserve listing.
14. Trade detail page and off-platform disclosure.
15. `TRD-02` cancellation.
16. `TRD-03` buyer completion confirmation.
17. `TRD-03` seller completion confirmation and mark sold.

Exit criteria:

- One individual listing cannot create two active trades.
- Payment/delivery responsibility is clear at every trade step.
- Chat access and evidence access are authorization-tested.

## 9. Phase 6: Business Cart and Inventory

1. `INV-01` inventory item creation from business listing.
2. Inventory adjustment API and movement history.
3. Seller portal inventory page.
4. Low-stock display.
5. `CRT-01` Redis cart read.
6. `CRT-02` add item.
7. `CRT-03` update/remove item.
8. Marketplace cart UI.
9. `CRT-04` authoritative cart validation.
10. `INV-02` reservation API.
11. Concurrent reservation test for one hot listing.
12. `INV-03` expiration worker.
13. `INV-04` commit reservation.

Exit criteria:

- Cart accepts business listings only.
- Price changes are surfaced.
- Supported concurrency cannot oversell inventory.

## 10. Phase 7: Checkout and Payment

1. `CHK-01` create checkout and immutable snapshots.
2. Checkout address page.
3. `CHK-02` subtotal and shipping calculation.
4. Add tax adapter interface and MVP implementation.
5. Checkout review page.
6. Select payment provider and build adapter.
7. `PAY-01` create payment intent.
8. Provider-hosted payment UI integration.
9. `PAY-02` signed webhook validation.
10. Webhook replay protection.
11. `ORD-01` create confirmed order.
12. Commit inventory after payment.
13. Publish order outbox events.
14. Checkout success/pending/failure pages.
15. `PAY-03` reconciliation job.
16. Admin payment/order mismatch queue.

Exit criteria:

- Client cannot control price.
- Duplicate submit/webhook cannot duplicate charge or order.
- Payment success without order is automatically recovered or queued.

## 11. Phase 8: Orders and Fulfillment

1. `ORD-02` buyer order list.
2. Buyer order detail.
3. `ORD-03` business order queue.
4. Business order detail.
5. `SHP-01` accept fulfillment.
6. `SHP-02` create shipment.
7. `SHP-03` mark shipped.
8. Buyer tracking timeline.
9. `SHP-04` carrier webhook or manual delivery state adapter.
10. Partial-shipment support.
11. `ORD-04` cancellation request.
12. Cancellation eligibility and inventory/refund orchestration.

Exit criteria:

- Buyer and business see correct scoped order views.
- State transitions reject invalid or duplicate commands.
- Shipment updates are idempotent.

## 12. Phase 9: Notifications

Implement notification infrastructure early where dependencies require
`NOT-01`, then complete channels here:

1. In-app event consumer and deduplication.
2. `NOT-02` notification list/read UI.
3. Versioned email templates.
4. `NOT-03` email delivery and retry.
5. Dead-letter operations view.
6. `NOT-04` preferences API and page.
7. End-to-end notification tests for listing, trade, payment, and shipping.

## 13. Phase 10: Reviews and Reputation

1. `REV-01` business order eligibility and review create.
2. Product review display.
3. Business seller review display.
4. `REV-02` individual trade review.
5. Individual reputation display.
6. `REV-03` aggregate projector and rebuild.
7. `REV-04` report review.
8. Moderator remove/restore review.

## 14. Phase 11: Admin and Trust Operations

1. Admin dashboard counts.
2. `ADM-03` generic reports queue.
3. Listing report detail.
4. Chat report detail with audited evidence access.
5. Individual trade report detail.
6. Business order support detail.
7. `ADM-04` suspend user.
8. Restore user.
9. Suspend/restore business.
10. `ADM-05` support cases and internal notes.
11. User-visible support responses.
12. `ADM-06` failed event and reconciliation operations.
13. Audit log search with bounded filters.

## 15. Phase 12: Limited AI

1. Agent service skeleton, authentication, budget, and audit.
2. `AI-01` `searchListings` tool.
3. `getListing` and compare response.
4. `AI-02` trade-message draft with safety policy.
5. `AI-03` listing-content draft.
6. `AI-04` read-only assigned support-case summary.
7. Prompt-injection and unauthorized-tool tests.
8. Cost, latency, and tool error dashboards.
9. Global AI disable switch.

## 16. Phase 13: Launch Readiness

1. End-to-end individual trade test.
2. End-to-end business purchase test.
3. Security and tenant isolation review.
4. Search load test.
5. Cart and reservation load test.
6. Checkout/payment resilience test.
7. Chat connection and message-rate test.
8. Backup restoration exercise.
9. Event replay and index rebuild exercise.
10. Observability dashboards and alerts.
11. Accessibility review of critical flows.
12. Incident and rollback runbooks.

## 17. Milestones

### Milestone A: Sellable listings

Phases 0 through 4. Sellers can onboard and publish moderated listings.

### Milestone B: Individual marketplace

Phase 5 plus required notifications and moderation. Individuals can negotiate
and record completed trades.

### Milestone C: Business commerce

Phases 6 through 9. Businesses can receive paid orders and fulfill them.

### Milestone D: Trust and operations

Phases 10 and 11. Reviews, reports, support, suspensions, and recovery are
operational.

### Milestone E: AI-assisted MVP

Phases 12 and 13. Limited AI is available, and the platform has passed launch
readiness checks.
