# MVP Requirements

## 1. Purpose

This document defines the approved minimum viable product for MSB E-Commerce.
It is the product-scope authority for MVP implementation.

The platform supports two transaction models:

1. **Individual trade**: a buyer and an individual seller negotiate price,
   payment, and delivery themselves. The platform records the agreement but
   does not process payment or shipping.
2. **Business order**: a buyer purchases from a verified business through the
   platform cart, checkout, payment, inventory, and shipping workflow.

These models may share users, listings, search, chat, reviews, notifications,
and moderation, but they must not share checkout state machines.

## 2. MVP Applications

### 2.1 Marketplace application

Audience:

- Guests
- Buyers
- Individual sellers

Responsibilities:

- Browse and search approved listings
- Display business and individual seller types clearly
- Manage buyer accounts
- Create individual listings
- Chat and negotiate individual trades
- Buy business listings through platform checkout
- Track trades and business orders
- Submit reviews and reports
- Receive notifications
- Use a limited shopping assistant

### 2.2 Business seller application

Audience:

- Business owners
- Authorized business staff

Responsibilities:

- Complete business onboarding
- Manage store profile and policies
- Manage staff permissions
- Manage listings and inventory
- Fulfill business orders
- View payment, fee, and payout status
- Respond to customer messages
- View basic store metrics

### 2.3 Admin application

Audience:

- Support agents
- Moderators
- Business reviewers
- Trust and safety staff
- Finance administrators
- Platform administrators

Responsibilities:

- Review businesses and listings
- Investigate reports and support cases
- Suspend or restore accounts within role permissions
- Inspect business orders and payment status
- Inspect individual trade records without promising payment recovery
- Review audit records and failed operations

## 3. Roles

User-facing roles:

- `BUYER`
- `INDIVIDUAL_SELLER`
- `BUSINESS_OWNER`
- `STORE_MANAGER`
- `CATALOG_MANAGER`
- `FULFILLMENT_STAFF`
- `CUSTOMER_SUPPORT`
- `FINANCE_VIEWER`

Platform roles:

- `SUPPORT_AGENT`
- `CONTENT_MODERATOR`
- `BUSINESS_REVIEWER`
- `TRUST_AND_SAFETY`
- `FINANCE_ADMIN`
- `PLATFORM_ADMIN`

A person has one user identity and may have multiple role assignments.
Business permissions are always scoped to a specific business.

## 4. Functional Requirements

Each numbered requirement is intended to be independently implementable and
testable unless a dependency is listed.

### 4.1 Identity and account

#### IAM-01 Register account

User flow:

1. Guest enters email, password, and display name.
2. System creates a disabled account and sends verification.
3. User verifies email.
4. System enables the buyer account.

Acceptance criteria:

- Email is unique after case normalization.
- Password is hashed and never returned.
- Verification tokens expire and are single-use.
- A new verified user receives the `BUYER` role.

#### IAM-02 Sign in and sign out

Acceptance criteria:

- Valid credentials create an authenticated session/token set.
- Invalid attempts return a generic error.
- Sign-out invalidates the refresh session.
- Repeated failures are rate-limited.

Depends on: `IAM-01`.

#### IAM-03 Recover password

Acceptance criteria:

- Recovery does not reveal whether an email exists.
- Reset tokens expire and are single-use.
- Successful reset revokes existing refresh sessions.

#### IAM-04 View and edit profile

Acceptance criteria:

- User can update display name, phone, and avatar.
- Email changes require reverification.
- Users cannot edit roles or account status.

#### IAM-05 Manage addresses

Acceptance criteria:

- User can add, update, delete, and choose a default address.
- An address used in an order is copied into an immutable order snapshot.

#### IAM-06 Enforce role and tenant authorization

Acceptance criteria:

- Every protected API verifies identity and required permission.
- Business APIs verify membership in the requested business.
- Admin APIs verify granular platform permissions.
- Cross-user and cross-business access returns `403`.

### 4.2 Individual seller activation

#### IND-01 Activate individual seller profile

User flow:

1. Buyer accepts individual-selling terms.
2. Buyer verifies required contact information.
3. System creates an active individual seller profile.

Acceptance criteria:

- No separate login is created.
- The user receives `INDIVIDUAL_SELLER`.
- The profile stores public location only at city/region precision.
- The platform clearly discloses that payment and delivery are off-platform.

Depends on: `IAM-04`.

### 4.3 Business onboarding and store

#### BUS-01 Start business application

Acceptance criteria:

- User selects business onboarding.
- Draft application stores legal and contact information.
- Applicant becomes the proposed business owner.

#### BUS-02 Submit business verification

Acceptance criteria:

- Required fields and agreements are validated.
- External verification references are stored instead of sensitive documents
  where possible.
- Status moves from `DRAFT` to `PENDING_VERIFICATION`.

Depends on: `BUS-01`.

#### BUS-03 Process verification callback

Acceptance criteria:

- Provider webhook signatures are verified.
- Duplicate callbacks have no duplicate effect.
- Application moves to `UNDER_REVIEW` or `VERIFICATION_FAILED`.

Depends on: `BUS-02`.

#### BUS-04 Admin business decision

Acceptance criteria:

- Authorized reviewer may approve, reject, or request more information.
- A reason is required.
- Approval activates the business and owner membership.
- Decision is audited and notifies the applicant.

Depends on: `BUS-03`, `ADM-01`, `NOT-01`.

#### BUS-05 Manage store profile

Acceptance criteria:

- Owner or store manager can edit store name, slug, description, logo, and
  customer contact data.
- Store slug is unique.
- Suspended businesses cannot publish listings.

Depends on: `BUS-04`.

#### BUS-06 Manage store policies

Acceptance criteria:

- Business can define shipping, cancellation, and return policy text.
- Policy versions are retained.
- Orders store the policy version applicable at purchase.

Depends on: `BUS-04`.

#### BUS-07 Manage business staff

Acceptance criteria:

- Owner can invite, change, and remove staff.
- Invitations expire.
- The last owner cannot remove their own owner access.
- Permissions are business-scoped.

Depends on: `BUS-04`, `IAM-06`.

### 4.4 Catalog, listings, and media

#### LST-01 Browse categories

Acceptance criteria:

- Guest can view active category hierarchy.
- Category response contains allowed listing attributes.

#### LST-02 Request image upload

Acceptance criteria:

- Authorized seller receives a short-lived signed upload request.
- File type and maximum size are constrained.
- Upload is not attached to a listing until confirmed.

#### LST-03 Confirm uploaded image

Acceptance criteria:

- System verifies object ownership and metadata.
- Unsafe or invalid files are rejected.
- Confirmed image can be attached only by its owner/business.

Depends on: `LST-02`.

#### LST-04 Create individual listing draft

Acceptance criteria:

- Active individual seller can create a draft.
- Listing includes title, description, category, condition, asking price,
  negotiable flag, approximate location, payment preferences, and delivery
  preferences.
- Individual quantity is exactly `1` in MVP.
- Seller owns the draft.

Depends on: `IND-01`, `LST-01`.

#### LST-05 Create business listing draft

Acceptance criteria:

- Authorized business catalog staff can create a draft.
- Listing includes business, SKU, price, quantity, condition, category,
  shipping profile, and return policy.
- Currency is explicit.

Depends on: `BUS-05`, `LST-01`.

#### LST-06 Edit listing draft

Acceptance criteria:

- Only the owning individual or authorized business member may edit.
- Optimistic locking prevents lost updates.
- Type-specific fields are validated.

Depends on: `LST-04` or `LST-05`.

#### LST-07 Attach and order images

Acceptance criteria:

- Seller can attach confirmed images and set display order.
- At least one image is required before submission.
- Removing an image does not expose another user's object.

Depends on: `LST-03`, `LST-06`.

#### LST-08 Submit listing for moderation

Acceptance criteria:

- Complete draft moves to `PENDING_REVIEW`.
- Seller cannot publish directly.
- Moderation case is created.

Depends on: `LST-07`, `ADM-02`.

#### LST-09 Moderate listing

Acceptance criteria:

- Moderator can approve, reject, or request changes.
- Approval sets listing to `ACTIVE`.
- Decision reason and actor are audited.
- Seller is notified.

Depends on: `LST-08`, `NOT-01`.

#### LST-10 Pause or close listing

Acceptance criteria:

- Owner can pause an active listing.
- Individual seller can mark a listing sold only through trade completion or
  an explicit off-platform close action.
- Listing with an active individual trade cannot be offered to another buyer.

#### LST-11 View listing details

Acceptance criteria:

- Guest can view only active, approved listings.
- Response clearly identifies `INDIVIDUAL` or `BUSINESS`.
- Individual listing displays off-platform payment disclosure.
- Business listing displays platform checkout availability.

### 4.5 Search

#### SRC-01 Index listing events

Acceptance criteria:

- Active listing changes produce search projections.
- Paused, sold, rejected, and suspended listings are removed from results.
- Failed indexing is retryable and observable.

Depends on: `LST-09`.

#### SRC-02 Search listings

Acceptance criteria:

- Search supports text, category, seller type, condition, price, and
  approximate location filters.
- Results use cursor pagination.
- Only active approved listings are returned.

Depends on: `SRC-01`.

#### SRC-03 Browse business storefront

Acceptance criteria:

- User can view an active business profile and its active listings.
- Suspended business storefronts are unavailable.

### 4.6 Chat, offers, and individual trades

#### CHT-01 Start listing conversation

Acceptance criteria:

- Signed-in buyer can start one conversation per individual listing/seller
  pair.
- Seller cannot start a buyer conversation with themselves.
- Conversation participants are fixed.

Depends on: `LST-11`.

#### CHT-02 Send and read text message

Acceptance criteria:

- Only conversation participants can read or send.
- Message length and rate limits apply.
- Message history is ordered and cursor-paginated.
- New message triggers notification.

Depends on: `CHT-01`, `NOT-01`.

#### CHT-03 Block or report participant

Acceptance criteria:

- Blocking prevents new messages.
- Reporting creates a moderation case with selected evidence.
- Reported user is not automatically punished.

Depends on: `CHT-02`, `ADM-03`.

#### OFF-01 Submit offer

Acceptance criteria:

- Buyer submits amount and optional delivery note within a conversation.
- Only active individual listings accept offers.
- Offer captures listing and buyer identity.

Depends on: `CHT-01`.

#### OFF-02 Respond to offer

Acceptance criteria:

- Recipient may accept, reject, or counter an open offer.
- Each response creates immutable offer history.
- Only one accepted offer may exist per listing.

Depends on: `OFF-01`.

#### TRD-01 Create trade from accepted offer

Acceptance criteria:

- Offer acceptance atomically creates a trade and reserves the listing.
- Other open offers close as `LISTING_UNAVAILABLE`.
- Trade stores agreed amount and delivery note.
- Platform displays that payment remains off-platform.

Depends on: `OFF-02`.

#### TRD-02 Cancel individual trade

Acceptance criteria:

- Participant can cancel before completion with a reason.
- Listing returns to active only when seller chooses to relist.
- Cancellation is audited and participants are notified.

Depends on: `TRD-01`.

#### TRD-03 Confirm individual trade completion

Acceptance criteria:

- Each participant may confirm completion independently.
- Trade becomes `COMPLETED` after both confirm.
- Seller can request admin review when confirmations conflict.
- Completion marks listing `SOLD`.

Depends on: `TRD-01`.

### 4.7 Business cart and inventory

#### CRT-01 View cart

Acceptance criteria:

- Authenticated user has one active cart.
- Cart contains only business listings.
- Response includes current availability and price-change warnings.

#### CRT-02 Add business listing to cart

Acceptance criteria:

- Only active business listings may be added.
- Quantity must be positive and within current available inventory.
- Adding the same listing updates its quantity.

Depends on: `CRT-01`.

#### CRT-03 Update or remove cart item

Acceptance criteria:

- User can change quantity or remove item.
- Cart operations are isolated by user.
- Expired carts may be recreated without affecting orders.

#### CRT-04 Validate cart

Acceptance criteria:

- Server reloads prices, listing status, seller status, and inventory.
- Client-supplied totals are ignored.
- Validation returns actionable item errors.

#### INV-01 Maintain business inventory

Acceptance criteria:

- Authorized business staff can set or adjust on-hand quantity.
- Each adjustment creates an inventory movement.
- Available quantity never becomes negative.

Depends on: `LST-05`.

#### INV-02 Reserve inventory

Acceptance criteria:

- Checkout atomically reserves available quantity.
- Reservation has an expiration time.
- Idempotency key prevents duplicate reservations.
- Concurrent buyers cannot oversell inventory.

Depends on: `CRT-04`, `INV-01`.

#### INV-03 Release expired reservation

Acceptance criteria:

- Expired active reservation is released once.
- Available quantity is restored.
- Repeated release is harmless.

#### INV-04 Commit reservation

Acceptance criteria:

- Paid order commits reservation once.
- Committed quantity reduces on-hand stock.
- Commit after release fails safely and triggers recovery review.

### 4.8 Business checkout, payment, and order

#### CHK-01 Create checkout session

Acceptance criteria:

- Server validates cart and address.
- Server creates immutable item and price snapshots.
- Inventory reservations are created.
- Session expiry matches reservation expiry.

Depends on: `IAM-05`, `CRT-04`, `INV-02`.

#### CHK-02 Calculate totals

Acceptance criteria:

- Server calculates subtotal, shipping, tax, discounts, and total.
- Currency is consistent within an order.
- Client totals are never trusted.

Depends on: `CHK-01`.

#### PAY-01 Create payment intent

Acceptance criteria:

- Payment service creates one provider intent per idempotency key.
- Card details never pass through platform servers.
- Provider reference and amount are persisted.

Depends on: `CHK-02`.

#### PAY-02 Process payment webhook

Acceptance criteria:

- Signature is verified.
- Event ID is deduplicated.
- Successful payment records an immutable payment event.
- Webhook processing does not depend on the buyer browser.

Depends on: `PAY-01`.

#### ORD-01 Create confirmed business order

Acceptance criteria:

- Successful payment produces an order exactly once.
- Multi-business cart creates separate seller fulfillment groups while
  retaining a buyer checkout reference.
- Inventory reservations are committed.
- Outbox events are created in the same transaction as state changes.

Depends on: `PAY-02`, `INV-04`.

#### PAY-03 Recover incomplete payment/order

Acceptance criteria:

- A reconciliation job detects payment success without confirmed order.
- Recovery is idempotent.
- Unrecoverable cases enter an admin operations queue.

#### ORD-02 View buyer orders

Acceptance criteria:

- Buyer sees only their orders.
- Order displays immutable address, price, policy, payment, and shipment
  snapshots.

#### ORD-03 View business orders

Acceptance criteria:

- Authorized business staff sees only that business's fulfillment groups.
- Financial fields follow staff permissions.

#### ORD-04 Cancel eligible order

Acceptance criteria:

- Buyer may request cancellation only before shipment and under stored policy.
- Cancellation updates payment/refund and inventory through explicit states.
- Duplicate requests are harmless.

### 4.9 Shipping and fulfillment

#### SHP-01 Accept order for fulfillment

Acceptance criteria:

- Authorized staff can accept a paid, unfulfilled order.
- State transition is validated and audited.

Depends on: `ORD-03`.

#### SHP-02 Add shipment

Acceptance criteria:

- Staff provides carrier, tracking number, and shipped items.
- Tracking number format is validated where supported.
- Order can support more than one shipment.

#### SHP-03 Mark shipment shipped

Acceptance criteria:

- Shipment moves to `SHIPPED`.
- Buyer receives notification.
- Repeated request is idempotent.

#### SHP-04 Process carrier event

Acceptance criteria:

- Provider event is authenticated and deduplicated.
- Shipment timeline is append-only.
- Delivery updates order status when all shipments are delivered.

### 4.10 Reviews and reputation

#### REV-01 Submit business review

Acceptance criteria:

- Buyer can review only a delivered order item.
- One active product review per order item.
- Rating is 1 through 5.

#### REV-02 Submit individual trade review

Acceptance criteria:

- Participant can review only a completed trade.
- One review per reviewer/trade.
- Review identifies individual-trade context.

#### REV-03 Display rating summary

Acceptance criteria:

- Product, business seller, and individual seller ratings are separate.
- Removed reviews do not contribute.
- Aggregate updates are eventually consistent.

#### REV-04 Report review

Acceptance criteria:

- Signed-in user may report a review once per reason.
- Report enters moderation queue.

### 4.11 Notifications

#### NOT-01 Create in-app notification

Acceptance criteria:

- Supported domain event creates at most one notification per recipient/event.
- Notification contains a safe application route, not arbitrary HTML.

#### NOT-02 Read notifications

Acceptance criteria:

- User can list, mark one read, or mark all read.
- Users cannot access another user's notifications.

#### NOT-03 Send email notification

Acceptance criteria:

- Required transactional events use versioned templates.
- Delivery failure retries with a bounded policy.
- Permanent failures enter a dead-letter queue.

#### NOT-04 Manage preferences

Acceptance criteria:

- User may opt out of optional email classes.
- Security, payment, and legal notifications cannot be disabled when required.

### 4.12 Administration, moderation, and support

#### ADM-01 Admin shell and authorization

Acceptance criteria:

- Admin application requires platform role and MFA-ready authentication.
- Unauthorized users cannot load protected data.
- Admin action includes actor and correlation ID.

#### ADM-02 Listing moderation queue

Acceptance criteria:

- Moderator can claim and resolve listing cases.
- Concurrent resolution is prevented.
- Evidence and decision history are retained.

#### ADM-03 Reports queue

Acceptance criteria:

- Authorized staff can review listing, user, chat, review, and trade reports.
- Case assignment and status changes are audited.

#### ADM-04 Suspend account or business

Acceptance criteria:

- Authorized role supplies reason and optional expiry.
- Suspension blocks new listings and transactions.
- Existing paid business orders remain visible for resolution.
- Restore is a separate audited action.

#### ADM-05 Support case notes

Acceptance criteria:

- Staff can add internal notes and user-visible responses.
- Internal notes are never exposed through marketplace APIs.

#### ADM-06 Operations queue

Acceptance criteria:

- Staff can inspect failed payment/order reconciliation and failed event
  delivery.
- Retrying an operation requires permission and is idempotent.

### 4.13 AI assistants

AI is not allowed to become a dependency of checkout, payment, inventory,
moderation decisions, or authentication.

#### AI-01 Search and compare listings

Acceptance criteria:

- Agent calls approved search/detail APIs.
- Response references current listing IDs.
- Price and availability come from tools, not model memory.

Depends on: `SRC-02`, `LST-11`.

#### AI-02 Draft individual trade message

Acceptance criteria:

- Agent produces text only.
- User edits or confirms before sending.
- Safety warning is shown for external payment or meeting topics.

#### AI-03 Draft seller listing content

Acceptance criteria:

- Agent may suggest title, description, category, and attributes.
- Output remains a draft.
- Seller explicitly confirms before saving.

#### AI-04 Support copilot summary

Acceptance criteria:

- Authorized support agent may summarize an assigned case.
- Tool access is read-only in MVP.
- Prompt, tool calls, output, actor, and case ID are audited.

## 5. Non-Functional Requirements

### 5.1 Capacity

Initial planning target:

- At least 100,000 registered users
- Horizontal scaling for stateless HTTP services
- No assumption of 100,000 simultaneous users
- Load tests must establish supported peak requests per second before launch

Initial service objectives:

- Public read API p95 under 500 ms, excluding external providers
- Authenticated command API p95 under 800 ms, excluding payment/AI providers
- Search p95 under 700 ms
- 99.9% monthly availability target for marketplace APIs
- Zero tolerated inventory oversell in tested supported concurrency

### 5.2 Security

- TLS for all external traffic
- Passwords hashed with an approved adaptive algorithm
- Least-privilege role and business authorization
- Rate limiting for authentication, chat, offers, and AI
- Secrets kept outside source control
- Payment details handled by the provider
- Sensitive actions recorded in immutable audit logs
- Input validation and output encoding
- Signed webhook verification and replay prevention

### 5.3 Reliability

- Idempotency on reservations, checkout, payments, orders, and webhooks
- Transactional outbox for reliable domain event publication
- Bounded retries and dead-letter handling
- Database backups and tested restore procedure
- Graceful degradation when AI, email, search, or chat realtime delivery fails

### 5.4 Observability

- Correlation ID across gateway, services, events, and audit records
- Structured logs without credentials or payment data
- Metrics for latency, error rate, saturation, queue lag, and business flows
- Distributed traces for checkout and order creation
- Alerts for payment/order mismatch, reservation failures, and event backlog

### 5.5 Accessibility and usability

- Keyboard-accessible primary flows
- Semantic labels and error messages
- Responsive web layouts
- Seller type and payment responsibility always visible
- Dates, money, and time zones rendered consistently

## 6. MVP Exclusions

The following are explicitly out of MVP:

- Platform payment or shipping for individual trades
- Escrow or buyer protection for individual trades
- Auctions
- Subscriptions
- Business bulk import
- Advanced promotions and loyalty programs
- Automated tax filing
- International marketplace rollout
- Native mobile applications
- Autonomous refunds, suspensions, moderation, or purchasing by AI
- AI-generated listing publication without human confirmation
- Full recommendation engine
- Live video or voice chat

## 7. MVP Completion Criteria

MVP is complete only when:

- All approved requirements have passing acceptance tests.
- Individual listing-to-completed-trade flow works without platform payment.
- Business listing-to-delivered-order flow works with idempotent payment.
- Inventory concurrency tests prove no overselling at supported load.
- Admins can review businesses, listings, reports, and failed operations.
- Security and tenant-isolation tests pass.
- Backup restoration and payment/order reconciliation are exercised.
- AI can be disabled without breaking any core marketplace flow.
