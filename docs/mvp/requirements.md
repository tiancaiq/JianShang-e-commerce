# Product Requirements and Release Scope

## 1. Purpose

This document defines the approved product requirements and release placement
for MSB E-Commerce. `development-roadmap.md` is the implementation-order
authority.

The platform supports two transaction models:

1. **Individual trade**: a buyer and an individual seller negotiate price,
   payment, and delivery themselves. The platform records the agreement but
   does not process payment or shipping.
2. **Business order**: beginning in V2, a buyer purchases from a verified
   business through platform cart, checkout, payment, inventory, and shipping.

These models may share users, listings, search, chat, reviews, notifications,
and moderation, but they must not share checkout state machines.

## 1.1 Release Classification

### MVP

- Foundation
- Authentication and user accounts
- Individual seller profile
- Business seller profile and basic store profile
- Business store item publishing for public browsing
- Listings and media
- Guest browsing, search, listing detail, and public storefront
- Basic text chat between buyer and individual seller
- Basic admin approval for businesses, individual listing moderation, and
  reactive safety removal for public business store items

### V2

- Cart
- Inventory
- Checkout and payment
- Orders and shipping
- Notifications

### V3

- Individual trade completion verification and completed-sales reputation
- Reviews and reputation
- Advanced admin and trust operations
- AI assistant
- Advanced analytics

Requirements remain documented for design continuity, but only requirements
classified as MVP may be scheduled before MVP is complete.

## 2. MVP Applications

The product has three user-facing surfaces:

1. **Public user marketplace site** for guests, buyers, and individual sellers.
2. **Business seller portal** for approved merchants to manage their store and
   basic listings.
3. **Admin portal** for platform staff to manage marketplace approval and
   moderation.

The MVP must make these surfaces visible as separate product areas, but it
does not need the full V2 commerce back office yet.

UI direction:

- The public marketplace should use a commerce browsing style similar to
  familiar large marketplace patterns: prominent search, category navigation,
  listing cards, image-forward detail pages, seller type labels, and clear
  calls to action.
- The business seller portal should use a management dashboard style focused
  on forms, tables, status badges, store/listing workflows, and operational
  clarity.
- The admin portal should use an internal operations style focused on queues,
  filters, decisions, audit context, and safe moderation actions.

Do not force the business seller portal or admin portal to look like the
public marketplace. Their users are doing operational work, not casual
shopping.

Detailed marketplace UI direction is maintained in
`docs/mvp/ui/marketplace-ui-redesign.md`.

### 2.1 Marketplace application

Audience:

- Guests
- Buyers
- Individual sellers

Responsibilities:

- Let guests browse, search, and view approved listings without signing in
- Present listings with shopping-oriented cards, images, search, categories,
  and detail pages
- Display business and individual seller types clearly
- Manage buyer accounts
- Activate and manage the user's individual seller profile
- Create, edit, submit, and track the user's individual listings
- Let signed-in buyers and individual sellers use basic text chat to
  negotiate individual deals

Deferred:

- Business checkout and order tracking: V2
- Trade completion reputation and reviews: V3
- AI shopping assistant: V3

### 2.2 Business seller application

Audience:

- Business owners
- Authorized business staff

Responsibilities:

- Complete business onboarding
- Manage basic store profile
- Manage basic business store items for public browsing
- Use dashboard-style pages for merchant work rather than shopping-style
  browsing pages
- Exclude personal individual-seller listing tools; those belong in the
  marketplace account experience

Deferred:

- Inventory, cart, checkout, payment, orders, shipping, fulfillment, and
  notifications: V2
- Store policy versioning: V3
- Advanced staff operations and analytics: V3

### 2.3 Admin application

Audience:

- Support agents
- Moderators
- Business reviewers
- Trust and safety staff
- Finance administrators
- Platform administrators

Responsibilities:

- Review and decide business applications
- Review and moderate listing submissions
- Use dashboard-style queues, tables, filters, and decision forms

Deferred to V3:

- Advanced reports, support cases, suspensions, finance, and operations

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

Release: V2.

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

Release: V3. MVP uses manual admin review of submitted business information.

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
- Decision is audited and visible when the applicant checks application status.

Depends on: `BUS-02`, `ADM-01`.

#### BUS-05 Manage store profile

Acceptance criteria:

- Owner or store manager can edit store name, slug, description, logo, and
  customer contact data.
- Store slug is unique.
- Suspended businesses cannot publish listings.
- Inventory, orders, payments, shipping, and fulfillment are not managed in
  the MVP store profile flow.

Depends on: `BUS-04`.

#### BUS-06 Manage store policies

Release: V3.

Acceptance criteria:

- Business can define shipping, cancellation, and return policy text.
- Policy versions are retained.
- Orders store the policy version applicable at purchase.

Depends on: `BUS-04`.

#### BUS-07 Manage business staff

Release: V3.

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
- Individual listing quantity is seller-entered and must be at least `1`.
- Seller owns the draft.

Depends on: `IND-01`, `LST-01`.

#### LST-05 Create business listing draft

Acceptance criteria:

- Authorized business catalog staff can create a draft.
- Listing includes business, store, SKU, price, display quantity, condition,
  category, and item description.
- Currency is explicit.
- Display quantity is catalog information in MVP. It is not an authoritative
  inventory balance and cannot reserve stock.

Depends on: `BUS-05`, `LST-01`.

#### LST-05A Publish business store item

Acceptance criteria:

- Approved business owner or authorized catalog staff can publish a complete
  business store item without item-level admin approval.
- Published business store items appear on the public `/stores` surface.
- Draft, paused, removed, and inactive-business items do not appear publicly.
- Public store item pages and cards do not show cart, checkout, payment,
  order, shipping, fulfillment, or transaction actions.
- Platform admins can remove public store items for policy/safety reasons
  without deleting item history.

Depends on: `LST-05`, `LST-07`.

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
- This applies to individual listing moderation. Business store items follow
  `LST-05A` self-publishing unless later policy scope changes.

Depends on: `LST-07`, `ADM-02`.

#### LST-09 Moderate listing

Acceptance criteria:

- Moderator can approve, reject, or request changes.
- Approval sets listing to `ACTIVE`.
- Decision reason and actor are audited.
- Decision is visible in the seller listing-management view.

Depends on: `LST-08`.

#### LST-10 Pause or close listing

Acceptance criteria:

- Owner can pause an active listing.
- Individual seller can mark a listing sold only through trade completion or
  an explicit off-platform close action.
- Listing with an active individual trade cannot be assigned to another buyer.

#### LST-11 View listing details

Acceptance criteria:

- Guest can view only active, approved listings.
- Response clearly identifies `INDIVIDUAL` or `BUSINESS`.
- Individual listing displays off-platform payment disclosure.
- Business listing identifies the business storefront. Platform checkout is
  added in V2.

### 4.5 Search

#### SRC-01 Index listing events

Acceptance criteria:

- Active listing changes produce search projections.
- Paused, sold, rejected, and suspended listings are removed from results.
- Failed indexing is retryable and observable.

Depends on: `LST-09`.

#### SRC-02 Search listings

Acceptance criteria:

- Public discovery is split into individual marketplace search and business
  storefront browse.
- Individual marketplace search defaults to active approved `INDIVIDUAL`
  listings and supports text, category, condition, price, and approximate
  location filters.
- Business storefront browse is scoped to one active approved business and
  supports its active published `BUSINESS` store items.
- Shared search/browse APIs may support seller type filtering, but UI entry
  points keep individual trade and business store experiences separate.
- Results use cursor pagination.
- Individual marketplace results return only active approved individual
  listings. Business storefront results return active published business store
  items.

Depends on: `SRC-01`.

#### SRC-03 Browse business storefront

Acceptance criteria:

- User can view an active business profile and its active listings.
- Suspended business storefronts are unavailable.
- Cart, inventory reservation, checkout, payment, orders, and shipping remain
  V2 even on business storefront pages.

### 4.6 Chat and individual trades

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
- Conversation list exposes unread state.

Depends on: `CHT-01`.

#### CHT-03 Block or report participant

Release: V3.

Acceptance criteria:

- Blocking prevents new messages.
- Reporting creates a moderation case with selected evidence.
- Reported user is not automatically punished.

Depends on: `CHT-02`, `ADM-03`.

#### TRD-01 Seller creates trade from conversation

Release: V3.

Acceptance criteria:

- Buyer and seller negotiate informally through text chat; the platform does
  not provide structured offer, counteroffer, or offer-acceptance actions.
- Only the listing owner can choose "Deal with this buyer".
- Seller starts the action from an existing conversation, so the buyer is
  derived from that conversation and cannot be replaced in the request.
- Trade creation atomically records that buyer and reserves the listing.
- Other conversations remain readable but cannot create another trade while
  the listing is reserved.
- Trade may store a seller-entered private deal note, but it is not treated as
  verified price, payment, or delivery evidence.
- Platform displays that payment remains off-platform.
- Seller cannot create a trade with themselves, a blocked user, or a buyer
  outside the selected listing conversation.

Depends on: `CHT-01`, `CHT-02`.

#### TRD-02 Cancel individual trade

Release: V3.

Acceptance criteria:

- Participant can cancel before completion with a reason.
- Listing returns to active only when seller chooses to relist.
- Cancellation is audited and participants are notified.

Depends on: `TRD-01`.

#### TRD-03 Confirm individual trade completion

Release: V3.

Acceptance criteria:

- Seller initiates completion for the buyer already recorded on the trade
  created from the selected conversation; seller cannot enter or select a
  different buyer identity.
- Seller sees only the buyer's public display name and a masked verified
  confirmation destination, such as `j***@mail.com` or `***-***-1234`.
- Seller confirms that the item was delivered to that buyer.
- Platform records seller confirmation and sends a single-use, expiring
  confirmation link or code to the buyer's verified email or phone.
- Full buyer email, phone, and address are never disclosed to the seller by
  this flow.
- Buyer must authenticate and confirm the matching trade. Possession of the
  link or code alone is insufficient.
- Repeated seller requests are rate-limited and invalidate older unused
  challenges without creating duplicate confirmations.
- Trade becomes `COMPLETED` only after both seller and buyer confirmations.
- Completion atomically marks the listing `SOLD` and increments the seller's
  public completed-sales count exactly once.
- A buyer rejection, expired challenge, or disputed handoff does not increment
  the completed-sales count.
- Seller can request admin review when completion is disputed.

Depends on: `TRD-01`.

### 4.7 Business cart and inventory (V2)

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

### 4.8 Business checkout, payment, and order (V2)

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

### 4.9 Shipping and fulfillment (V2)

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

### 4.10 Reviews and reputation (V3)

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
- Individual seller public reputation includes `completedSalesCount`.
- `completedSalesCount` is the number of distinct completed individual trades,
  not listing closes, seller-only confirmations, cancelled trades, or business
  orders.
- The count can be rebuilt from completed trade records.

#### REV-04 Report review

Acceptance criteria:

- Signed-in user may report a review once per reason.
- Report enters moderation queue.

### 4.11 Notifications (V2)

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

`ADM-01` and `ADM-02` are MVP. `ADM-03` through `ADM-06` are V3.

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

### 4.13 AI assistants (V3)

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
- Rate limiting for authentication, chat, trade creation, and AI
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
- Individual trade completion verification and public sales reputation
- Business cart, inventory, checkout, payment, orders, and shipping
- User notification center and notification preferences
- Reviews and ratings
- Advanced reports, disputes, suspensions, support, and finance operations
- AI assistants
- Advanced analytics
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

- All MVP-classified requirements have passing acceptance tests.
- Users can register, sign in, and manage basic accounts.
- Individual and business sellers can establish the approved basic profiles.
- Sellers can create moderated listings with media.
- Guests can search listings and view public storefronts.
- Buyer and individual seller can exchange basic text chat messages.
- Admins can approve/reject businesses and listings.
- Security and tenant-isolation tests pass.
- No V2 or V3 user-facing feature is required for MVP completion.
