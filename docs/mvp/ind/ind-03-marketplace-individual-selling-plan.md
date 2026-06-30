# IND-03 Marketplace Individual Selling Plan

Status: planned.

## Goal

Move individual seller listing management into the public marketplace user
site experience. Individual sellers should not use the business seller portal
to activate their personal seller profile, create personal listings, edit
personal listing drafts, submit personal listings for review, or check personal
listing status.

The business seller portal remains for approved businesses only.

## Product Decision

Individual sellers are regular marketplace users with extra selling tools in
their marketplace account area.

Business sellers are merchants with a separate management portal.

Do not merge these experiences:

- Individual seller: marketplace site, personal account, one-off used/new item
  listings, and off-platform buyer/seller chat.
- Business seller: business portal, store profile, business listings, and
  later V2 inventory/orders/payment operations.

## MVP User Flow

1. Guest browses marketplace listings without login.
2. User signs in from the marketplace site.
3. User opens `Sell` or `My Listings` from marketplace navigation.
4. If the user has no individual seller profile, show individual seller
   activation.
5. After activation, user can create a personal listing draft.
6. User adds listing details and media.
7. User submits the listing for admin moderation.
8. User can return to `My Listings` to see draft, submitted, approved, or
   rejected listings.
9. Approved listings appear in public marketplace browse/detail pages.
10. Buyer contact/chat happens from the public listing detail page after sign
    in.

## Frontend Route Target

Target marketplace routes:

```text
/sell
/account/listings
/account/listings/new
/account/listings/:listingId/edit
/account/seller-profile
```

Legacy compatibility routes may remain temporarily until the UI migration is
complete:

```text
/seller/activate
/seller/listings
/seller/listings/new
/seller/listings/:listingId/edit
```

The legacy routes should redirect to the marketplace routes after the new
marketplace pages exist.

## Frontend Pages And Components

Marketplace layout:

- `Sell` entry in the public marketplace header.
- `My Listings` entry for signed-in users.
- Account navigation for profile, seller profile, listings, and messages.

Individual seller pages:

- Seller activation page.
- My listings list page.
- Create listing draft page.
- Edit listing draft page.
- Listing submission status panel.
- Rejection reason panel.

Shared components:

- Listing form.
- Media upload section.
- Listing status badge.
- Seller type badge.
- Off-platform trade notice.

Business seller portal must not show individual seller personal listing tools.

## Backend APIs

Reuse existing protected APIs where possible:

```text
GET    /api/v1/users/me/individual-seller-profile
POST   /api/v1/users/me/individual-seller-profile
GET    /api/v1/users/me/listings
POST   /api/v1/listings
GET    /api/v1/listings/{listingId}
PATCH  /api/v1/listings/{listingId}
POST   /api/v1/listings/{listingId}/submit
```

The API path does not need to change only because the frontend route changes.
Authorization remains backend-enforced.

## Database Impact

No new tables are required for the route/UI migration.

The plan uses existing individual seller profile, listing, listing image, and
moderation state tables.

Add a migration only if the implementation discovers a missing persisted field
that is required for marketplace account listing status.

## Agent-Service Logic

No agent-service logic is needed for MVP.

AI listing help, pricing suggestions, support automation, or moderation
assistance remains V3.

## Edge Cases

- Signed-in user clicks `Sell` but has no individual seller profile.
- User tries to edit a listing owned by another seller.
- User tries to edit a submitted, approved, rejected, or archived listing when
  only drafts are editable.
- User has both an individual seller profile and business membership.
- Business user accidentally lands on marketplace `My Listings`.
- Legacy `/seller/...` route is bookmarked.
- User opens listing edit page after the listing was moderated in another tab.
- Media upload succeeds but listing save fails.
- Rejected listing has no usable decision reason.

## Tests

Frontend:

- Marketplace header shows `Sell`/`My Listings` in the correct session states.
- `/sell` sends users without an individual seller profile to activation.
- `/account/listings` shows only the current user's individual listings.
- Business portal navigation does not show individual personal listing tools.
- Legacy `/seller/...` routes redirect or remain backward-compatible during
  migration.

Backend/API:

- Existing individual listing authorization tests continue to pass.
- Cross-user listing edit is forbidden.
- Business membership does not grant access to another user's individual
  listings.
- Submitted/non-draft listing edit returns the expected error.

End-to-end/manual:

- User signs in on marketplace, activates individual seller profile, creates a
  draft, edits it, submits it, and sees status in marketplace `My Listings`.
- Business seller signs in to the business portal and sees only business/store
  tools.

## Acceptance Criteria

- Individual sellers can complete all MVP personal listing work from the
  marketplace site.
- Individual sellers do not need the business seller portal.
- Business seller portal is business-only.
- Existing backend authorization still distinguishes individual listing
  ownership from business membership.
- Public listing browse/detail still distinguishes `Individual` and
  `Business` seller types.
- No cart, checkout, inventory, orders, shipping, payment, wallet, or AI
  functionality is introduced.

## Non-Goals

- Business inventory.
- Cart or checkout.
- Platform payment.
- Orders or shipping.
- Reviews or completed-sales reputation.
- Structured individual offers/counteroffers.
- AI listing assistant.
