# Pre-Search Manual Browser Smoke Checklist

Use this checklist before starting search implementation or when handing a
local demo to another teammate.

## Local Services

Expected local URLs:

- Frontend: `http://localhost:4200`
- API gateway: `http://localhost:9000`
- Keycloak: `http://localhost:8181`

Minimum services:

- Keycloak realm `msb-local`
- MySQL schemas used by `auth-service` and `product-service`
- `auth-service`
- `product-service`
- `api-gateway`
- Angular frontend

Use an account that can sign in through the marketplace auth dialog. For admin
review checks, use an account with platform admin permission.

## Auth Smoke

1. Open `http://localhost:4200`.
2. Click `Login`.
3. Sign in with a local marketplace account.
4. Confirm the nav shows authenticated links such as `My Listings` and
   `Account`.
5. Open `http://localhost:9000/api/v1/auth/session`.
6. Confirm the session response is authenticated and does not expose access or
   refresh tokens.
7. Click `Logout`.
8. Confirm the marketplace returns to an unauthenticated nav state.

## Protected Route Return

1. Open `http://localhost:4200/account/listings/new` while logged out.
2. Sign in when prompted.
3. Confirm the browser returns to the account listing flow after login.

## Individual Seller Activation

1. Open `http://localhost:4200/account/profile`.
2. Activate the individual seller profile if it is not already active.
3. Confirm the profile shows active seller status and the seller can open
   `http://localhost:4200/account/listings/new`.

## Draft Listing With Images

1. Open `http://localhost:4200/account/listings/new`.
2. Create an individual listing draft with title, description, condition,
   price, quantity, city, county, and at least one image.
3. Confirm image previews appear in the form before saving.
4. Click `Save draft`.
5. Confirm the draft is shown under `http://localhost:4200/account/listings`.
6. Reopen the draft and confirm saved images are still visible.

## Submit For Review

1. Open a saved draft with at least one image.
2. Click `Submit for review`.
3. Confirm the listing status becomes `PENDING_REVIEW`.
4. Without changing any field, confirm submit remains disabled or blocked.
5. Change a field and confirm resubmission is available.

## Admin Listing Approval

1. Sign in as a platform admin.
2. Open `http://localhost:4200/admin/listings/moderation`.
3. Open the pending listing review.
4. Approve the listing.
5. Confirm the listing becomes `ACTIVE` and `APPROVED`.

## Public Marketplace Browse

1. Open `http://localhost:4200/marketplace` as a guest or signed-in user.
2. Confirm approved active individual listings appear in the marketplace grid.
3. Confirm draft, pending-review, rejected, and closed listings do not appear.
4. Confirm business listings are not shown in the individual marketplace grid.

## Business Stores Browse

1. Open `http://localhost:4200/stores`.
2. Confirm approved active business listings are grouped by store.
3. Confirm individual listings are not shown on the stores page.

## Public Listing Detail

1. Click an approved public listing from the marketplace or stores page.
2. Confirm the detail page is guest-readable.
3. Confirm the image gallery loads approved images.
4. Confirm seller type, seller display name, condition, price, quantity, and
   public city/county are visible.
5. Confirm the page does not show private owner IDs, raw object-storage keys,
   access tokens, refresh tokens, or moderation internals.

## Debug Links

- Auth/session behavior: `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- Marketplace UI: `docs/mvp/ui/marketplace-ui-redesign.md`
- Site split: `docs/mvp/site/site-01-public-ui-surface-split.md`
- Listing edit flow: `docs/mvp/list/list-04-edit-listing-draft.md`
- Listing review/admin flow: `docs/mvp/list/list-06-admin-listing-moderation-decision.md`
- Media storage: `docs/mvp/list/media-01-object-storage-image-delivery.md`
