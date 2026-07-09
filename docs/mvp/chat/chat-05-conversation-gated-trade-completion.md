# CHAT-05 Conversation-Gated Trade Completion

Status: complete.

## Goal

Add a minimal individual listing completion flow where the existing listing
conversation is the required proof of buyer/seller relationship.

This is an explicit scope expansion from the earlier V3 trade-completion
placement. The implementation remains small: no structured offers, payment,
shipping, buyer contact sharing, reviews, completed-sales reputation count, or
platform protection claims are added.

## Scope

In scope:

- Seller marks a listing conversation done from the existing chat.
- Buyer confirms completion from that same chat.
- Buyer identity is derived from the conversation and is never typed or
  selected manually.
- Seller records how many units were sold; the quantity defaults to one and
  cannot exceed the listing quantity.
- Chat-service stores listing, conversation, seller, buyer, and completion
  status.
- Buyer confirmation closes the product listing so it disappears from public
  marketplace/search.
- Closed listings remain visible in the seller's own listing history.
- Seller mark-done and buyer confirm are idempotent.

Out of scope:

- Structured offer, counteroffer, or offer acceptance.
- Buyer email, phone, address, or manual buyer selection.
- Payment, escrow, shipping, order, inventory, or platform protection.
- Public completed-sales reputation increments.
- Reviews.
- Disputes, challenges, notifications, or admin completion review.

## Product Rules

- Completion must start from an existing `LISTING_BUYER_SELLER` conversation.
- Current user must be the seller participant to mark done.
- Current user must be the buyer participant to confirm.
- The conversation must belong to the listing being completed.
- Product-service closes only active approved individual listings whose owner
  matches the seller derived by chat-service.
- A listing with a completion in one conversation cannot be completed from a
  different conversation.

## API Contract

New chat endpoints:

```text
GET  /api/v1/conversations/{conversationId}/completion
POST /api/v1/conversations/{conversationId}/completion/mark-done
POST /api/v1/conversations/{conversationId}/completion/confirm
```

`GET /api/v1/conversations/{conversationId}` also includes a `completion`
object so the UI can render allowed actions without accepting user-supplied
buyer IDs.

Statuses:

- `NOT_STARTED`
- `SELLER_MARKED_DONE`
- `BUYER_CONFIRMED`
- `CANCELLED`

Internal product-service endpoint used only by chat-service after buyer
confirmation:

```text
POST /api/v1/internal/chat/listings/{listingId}/complete-trade
```

The request carries the conversation, seller, and buyer IDs derived by
chat-service. Product-service validates listing ownership and state before
closing the listing.

## Persistence

Chat-service owns `listing_trade_completions`.

Important columns:

- `id`
- `listing_id`
- `conversation_id`
- `seller_user_id`
- `buyer_user_id`
- `quantity_sold`
- `status`
- `seller_marked_done_at`
- `buyer_confirmed_at`
- `cancelled_at`
- `version`
- timestamps

The table has a unique constraint on `conversation_id`, making repeated
seller mark-done requests naturally idempotent.

## Frontend

- Full messages page and floating chat show `Mark as done` for eligible
  sellers.
- The buyer sees `Confirm completed` after seller mark-done.
- Banners explain the current completion state.
- My Listings/account preview labels closed completed listings as
  `Completed / closed`.

## Verification

Covered by focused tests:

- no chat means seller cannot mark done
- unrelated user cannot confirm
- seller cannot confirm as buyer
- buyer can confirm only from their conversation
- duplicate seller mark-done creates one completion
- seller cannot mark done for more than listing quantity
- a different conversation cannot complete the same listing
- duplicate buyer confirm calls product close once
- confirmed listing disappears from public marketplace search
- confirmed listing remains in seller listing history
