# CHAT-07 Repeatable Trade Completion Demo Hardening

Status: complete.

## Goal

Make the existing CHAT-05 and CHAT-06 individual trade completion flow clear,
repeatable, and testable in a real browser without adding payments, orders,
reviews, or a second completion state machine.

## Scope

In scope:

- Stable local demo buyer and individual seller identities with different
  names and unique public handles.
- One approved active individual demo listing owned by the demo seller.
- Participant display as `Display Name @public-handle`.
- A four-stage chat tracker:
  `Discussing`, `Seller marked done`, `Awaiting buyer`, `Completed`.
- Seller waiting copy with no repeat seller action after mark-done.
- A buyer confirmation review showing item, quantity, seller, and the explicit
  off-platform payment warning.
- Completed conversations become `LOCKED` and read-only.
- The completed seller view links to `/account/listings`.
- Duplicate seller mark-done and buyer confirmation remain idempotent.
- Playwright coverage for buyer login, chat, seller login, mark-done, buyer
  confirmation, refresh behavior, public listing removal, and seller history.

Out of scope:

- Platform payment, payment verification, checkout, orders, shipping, reviews,
  reputation increments, structured offers, or buyer contact sharing.
- Business-item completion.
- Realtime message delivery.

## Local Demo Fixtures

The local `msb-local` realm contains:

| Role | Login | Password | Public label |
|---|---|---|---|
| Individual seller | `trade.seller@msb.local` | `TradeSeller!2026` | `Mira Chen @mira-trades` |
| Buyer | `trade.buyer@msb.local` | `TradeBuyer!2026` | `Jon Bell @jon-buys` |

The fixed listing is:

- ID: `01D00000000000000000000101`
- Title: `Walnut desktop radio with warm dial light`
- Seller: `Mira Chen @mira-trades`
- Initial state: `ACTIVE` and `APPROVED`

These credentials are local demo fixtures and must not be reused as production
credentials.

## State And Authorization Rules

- Only the listing seller can mark the conversation done.
- The buyer is derived from the existing listing conversation.
- Only that buyer can confirm.
- Duplicate commands return the existing completion result.
- Buyer confirmation closes the product listing and locks the conversation.
- Locked conversations reject new messages from both participants.
- The closed listing remains in seller history and disappears from public
  marketplace and search responses.

## Verification

Automated coverage includes:

- participant public handles without private identity fields
- seller mark-done and buyer-confirm idempotency
- message rejection after completion
- read-only Angular states in full and floating chat
- buyer confirmation review content
- seller-only completed-history link
- browser account switching, refreshes, completion, and public removal

Run the browser test from `frontend`:

```powershell
npm.cmd run e2e
```

Keycloak, MySQL, gateway, auth-service, product-service, chat-service, and the
Angular frontend must already be running. Environment overrides for the
fixture setup are documented in `frontend/e2e/global-setup.ts`.
