# CHAT-06 Transaction Done Flow Verification

Status: complete.

## Goal

Verify the `transaction done` flow end to end and harden the CHAT-05 contract
for listings whose quantity can be greater than one.

## Scope

In scope:

- Seller opens an existing listing chat and sees `Mark as done`.
- Seller mark-done stores `SELLER_MARKED_DONE` and is idempotent.
- Seller supplies `quantitySold`; chat-service validates it is at least one
  and no more than the listing quantity.
- Buyer opens the same chat, sees `Confirm completed`, and confirms.
- Buyer confirmation stores `BUYER_CONFIRMED`.
- Buyer confirmation closes the listing, removes it from public
  marketplace/search, and keeps it in seller listing history.
- My Listings/account preview shows closed completed listings clearly.
- Floating chat and the full messages page expose the same completion actions
  and status copy.

Out of scope:

- Partial inventory decrement or keeping a listing open after a partial sale.
- Payment, shipping, order, platform protection, reviews, reputation count, or
  structured offers.

## Contract Notes

- `POST /api/v1/conversations/{conversationId}/completion/mark-done` accepts:

```json
{"quantitySold": 1}
```

- `quantitySold` defaults to `1` when omitted for backward compatibility.
- Completion responses include `quantitySold` once the seller marks done.
- Buyer, seller, listing, and conversation IDs remain server-derived from the
  fixed `LISTING_BUYER_SELLER` conversation.
- Product-service still closes the listing after buyer confirmation; MVP does
  not decrement quantity and keep the listing active.

## Verification

Covered by focused tests:

- no chat means seller cannot mark done
- seller cannot mark done for more than listing quantity
- different conversation cannot complete the same listing
- unrelated user cannot confirm
- seller cannot confirm as buyer
- buyer confirms only after seller mark-done
- duplicate seller mark-done creates one completion
- duplicate buyer confirm is safe
- confirmed listing disappears from public marketplace search
- confirmed listing remains in seller listing history
- full messages page and floating chat call mark-done with quantity
