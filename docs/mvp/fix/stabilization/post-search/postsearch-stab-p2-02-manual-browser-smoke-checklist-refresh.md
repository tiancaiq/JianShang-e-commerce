# POSTSEARCH-STAB-P2-02 Manual Browser Smoke Checklist Refresh

Status: Complete  
Date: 2026-07-19

## Goal

Give teammates a current manual browser smoke checklist for the MVP after
profile/avatar, split marketplace search, business storefront search, cursor
pagination, and OpenSearch projection work.

This cleanup is documentation-only. It does not stage files, edit environment
files, change application behavior, or add new tests.

## Preconditions

Use this checklist after the MVP services are running locally or in the demo
environment.

Expected local entry points:

- Marketplace: `http://localhost:4200/marketplace`
- Seller portal: `http://localhost:4200/seller`
- Admin portal: `http://localhost:4200/admin`
- Gateway: `http://localhost:9000`
- Keycloak local realm: `http://localhost:8181/realms/msb-local`

Required accounts:

- Guest browser session.
- Normal marketplace user.
- Individual seller user, or a user who can activate an individual seller
  profile.
- Business seller user, if business storefront checks are included.
- Platform admin user for moderation checks.

Do not use real payment, shipping, order, or inventory flows. Those are V2.

## Smoke Checklist

### 1. Guest Marketplace Browse

1. Open `http://localhost:4200/marketplace` in a fresh browser session.
2. Confirm listing cards load without login.
3. Confirm cards show public-safe data only:
   - title
   - image or fallback image area
   - price and currency
   - condition
   - seller display label
   - public pickup area, not exact private address
4. Confirm there is no cart, checkout, payment, order, shipping, review,
   reputation, trade-completion, or AI assistant requirement in the guest flow.

Pass criteria:

- Guest can browse approved public listings.
- Guest cannot see private seller data.

### 2. Individual Marketplace Search

1. Use the marketplace search input.
2. Search by a known listing keyword.
3. Apply filters one at a time:
   - category
   - condition
   - min price
   - max price
   - city
   - county
   - sort
4. Refresh the page.
5. Use the browser back and forward buttons.

Pass criteria:

- Search state survives refresh/back/forward through URL parameters.
- Results remain individual marketplace listings only.
- Results do not show business-only catalog or inventory controls.

### 3. Cursor Load More

1. Search or browse a result set with more than one page.
2. Click `Load more`.
3. Confirm new cards append below existing cards.
4. Confirm existing cards do not disappear.
5. Confirm duplicate cards do not appear.
6. Change a filter and confirm the list resets to the first page.

Pass criteria:

- Pagination is stable and append-only within one search.
- Filter changes start a new result set.

### 4. Listing Detail

1. Open a public listing detail page from a marketplace card.
2. Confirm listing title, media, description, condition, category, price, and
   public seller label are visible.
3. Confirm individual listings show off-platform trade language.
4. Confirm private seller fields are not visible:
   - user ID
   - Keycloak subject
   - email
   - phone
   - exact address
   - storage bucket
   - object key
   - moderation internals

Pass criteria:

- Public detail is buyer-friendly but private-data safe.
- Individual listings do not imply platform checkout or payment protection.

### 5. Login And Logout

1. Open the marketplace login dialog.
2. Sign in with a normal marketplace user.
3. Confirm the marketplace session appears active.
4. Open account/profile routes.
5. Log out from the marketplace navigation.
6. Confirm the browser returns to the marketplace site, not the gateway login
   page.
7. Confirm the signed-out confirmation appears once and the URL is cleaned up
   where the UI supports it.

Pass criteria:

- Browser JavaScript does not expose OAuth access, refresh, or ID tokens.
- Logout returns to the marketplace surface.
- Protected routes require a fresh login after logout.

### 6. Profile Save

1. Sign in.
2. Open account/profile.
3. Edit display name or another MVP profile field.
4. Save.
5. Refresh the page.

Pass criteria:

- Updated profile value persists.
- The profile page keeps the marketplace visual style.
- Profile save does not expose role, token, or provider internals.

### 7. Avatar Upload, Remove, And Display

1. Sign in.
2. Open account/profile.
3. Upload a valid small image.
4. Confirm upload succeeds and the profile image updates.
5. Refresh the page and confirm the avatar still displays.
6. Open a public listing or seller label that should show the avatar.
7. Remove the avatar if the UI supports removal.
8. Refresh again.

Pass criteria:

- Avatar display uses app-owned public avatar URLs.
- Browser direct upload does not send gateway cookies or CSRF headers to object
  storage.
- Public avatar delivery does not expose raw storage bucket, object key, or
  signed URL.
- Removed avatar falls back cleanly.

### 8. Business Storefront Search

1. Open `http://localhost:4200/stores`.
2. Search for a known business listing or store.
3. Open a business store profile if available.
4. Apply filters and sorting.
5. Use refresh/back/forward.

Pass criteria:

- Storefront search shows business listings only.
- Inactive, paused, removed, or draft business items do not appear.
- The guest storefront does not expose cart, inventory reservation, checkout,
  payment, orders, or shipping.

### 9. Seller Portal Boundary

1. Sign in as a seller-capable user.
2. Open `http://localhost:4200/seller`.
3. Confirm the seller portal uses management-style layout.
4. Open store/listing management pages that are part of MVP.
5. Confirm individual seller listing work still belongs under marketplace
   account pages, not the business seller portal.

Pass criteria:

- Marketplace and business seller portal remain visually and functionally
  distinct.
- V2 inventory routes are not part of active MVP navigation.

### 10. Admin Moderation

1. Sign in as a platform admin.
2. Open business application moderation.
3. Open listing moderation.
4. Review one pending item without changing state, or use a known test item.
5. Confirm decision forms require reason/context where applicable.
6. Sign in as a non-admin and attempt to open admin routes.

Pass criteria:

- Admin pages use management-style layout.
- Non-admin users cannot access admin routes.
- Moderation pages do not expose unrelated private user/contact data.

### 11. OpenSearch Disabled Mode

1. Run the MVP with OpenSearch disabled or unavailable.
2. Open marketplace browse/search.
3. Open business storefront search.

Pass criteria:

- Database-backed search remains the default public behavior.
- Users do not see raw infrastructure errors.

### 12. OpenSearch Enabled Mode

Only run this if the local/demo stack intentionally enables OpenSearch.

1. Enable the documented OpenSearch configuration.
2. Confirm the search index is available.
3. Run marketplace and business storefront searches.
4. Stop or break OpenSearch in a controlled local environment.

Pass criteria:

- OpenSearch returns candidate IDs only.
- Product-service revalidates visibility against MySQL before responding.
- When search depends on OpenSearch and it is unavailable, the API returns the
  documented `LISTING_SEARCH_UNAVAILABLE` behavior instead of leaking internals.

## Explicit Non-Goals

- No checkout smoke.
- No cart smoke.
- No inventory smoke.
- No order or shipping smoke.
- No notification smoke.
- No review or reputation smoke.
- No trade-completion smoke.
- No AI assistant smoke.

Those flows belong to V2 or V3 unless the roadmap changes.

## Useful Commands

Read-only discovery:

```powershell
rg -n "smoke|avatar|search|OpenSearch|load more|logout|admin" docs/mvp/fix docs/deploy
```

Automated baseline to run before or after manual smoke:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false
npm.cmd run build
cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

## Acceptance Criteria

- A teammate can smoke-test the current MVP without reading chat history.
- The checklist covers auth, profile, avatar, public marketplace search,
  business storefront search, cursor pagination, listing detail, admin
  moderation, and OpenSearch mode differences.
- The checklist explicitly excludes V2 and V3 flows.
- No code or environment file changes are made by this cleanup.
