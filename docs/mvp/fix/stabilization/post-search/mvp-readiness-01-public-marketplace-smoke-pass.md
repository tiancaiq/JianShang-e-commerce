# MVP-READINESS-01 Public Marketplace Smoke Pass

Status: complete.

## Goal

Verify that the current MVP public marketplace surface has a clean automated
baseline before continuing more feature work.

This slice is a readiness pass, not a feature slice. It does not add checkout,
cart, orders, shipping, notifications, reviews, AI, or structured offer flows.

## Scope Checked

- Public marketplace route shape:
  - `/marketplace`
  - `/stores`
  - `/listings/:listingId`
- Protected marketplace account routes:
  - `/account`
  - `/account/profile`
  - `/account/listings`
  - `/account/messages`
- Frontend marketplace layout, public listing detail, account shell, chat
  launcher, auth session awareness, and listing API client behavior.
- Backend auth, gateway, listing, search, media, chat eligibility, chat
  completion, and business store public-read behavior.

## Findings And Fixes

1. Public store profile reads were blocked by auth-service security.
   - Fix: permit `GET /api/v1/stores/{slug}` through auth-service security.
   - File: `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
   - Why: public store browsing is part of the MVP guest marketplace surface.

2. One product-service regression test asserted that public marketplace search
   should be globally empty after one completed listing trade.
   - Fix: assert that the completed listing ID is absent from public search
     while other approved public listings may remain visible.
   - File:
     `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
   - Why: completing one trade should close that listing, not hide unrelated
     approved listings.

## Verification

Frontend focused smoke:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --progress=false --include src/app/app.routes.spec.ts --include src/app/core/services/auth.service.spec.ts --include src/app/core/services/listing.service.spec.ts --include src/app/core/services/chat.service.spec.ts --include src/app/features/marketplace/marketplace-home.component.spec.ts --include src/app/features/marketplace/public-listing-detail.component.spec.ts --include src/app/features/account/account.component.spec.ts --include src/app/features/account/user-profile-card.component.spec.ts --include src/app/features/account/conversation-shell.component.spec.ts --include src/app/features/chat/floating-chat.component.spec.ts --include src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Result:

- Passed: 111 specs.
- Note: test server logged expected `404` warnings for mocked listing media and
  avatar image URLs.

Frontend production build:

```powershell
cd frontend
npm.cmd run build
```

Result:

- Passed.
- Output: `frontend/dist/frontend`.

Backend readiness:

```powershell
.\mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

Result:

- Passed: 292 tests.
- Modules:
  - `common-core`: 11 passed.
  - `common-web`: 16 passed.
  - `common-storage`: 2 passed.
  - `common-testing`: 3 passed.
  - `product-service`: 115 passed.
  - `chat-service`: 26 passed.
  - `api-gateway`: 54 passed.
  - `auth-service`: 65 passed.

## Manual Browser Smoke

Manual browser smoke was not completed in this slice because
`http://127.0.0.1:4200/marketplace` was not reachable when checked.

Command result:

```text
unreachable: Unable to connect to the remote server
```

Use the existing test account only when the local demo stack is running:

- username/email: `shen.ban2@mycnmipss.org`
- password: local test password shared in the development chat

## Git State Note

The working tree was already dirty before this slice, including modified
`.env`, frontend, backend, compose, and documentation files plus untracked chat
and engagement work. This slice did not clean or delete those files.

## Acceptance Criteria

- Automated public marketplace frontend smoke passes.
- Production frontend build passes.
- Backend gateway/auth/product/chat tests pass.
- Public store profile reads are guest-accessible.
- Completed individual listings are removed from public search without hiding
  unrelated approved listings.
- No V2/V3 product features are introduced.

## Follow-Up

Run a manual browser smoke once the local or demo stack is running:

- guest marketplace browse
- guest stores browse
- guest listing detail
- login/logout
- account profile
- my listings
- conversation entry point from an approved individual listing
