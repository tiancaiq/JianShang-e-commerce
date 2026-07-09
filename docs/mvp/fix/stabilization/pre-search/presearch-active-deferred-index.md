# Pre-Search Active And Deferred Code Index

This index helps reviewers distinguish active MVP surfaces from older tutorial
or deferred V2 code after profile, avatar, search, and OpenSearch work.

Source of truth remains:

- `AGENTS.md`
- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/database.md`
- `docs/mvp/development-roadmap.md`

## Active MVP Backend Modules

- `api-gateway`: gateway BFF session, auth bridge, CSRF, and external API
  routing.
- `auth-service`: user profile, individual seller profile, business
  application, business membership, and platform-admin identity APIs.
- `product-service`: categories, listing drafts, listing media, seller listing
  lifecycle, public listing browse/detail, split marketplace/store search,
  OpenSearch-derived public listing projection, and listing moderation.
- `chat-service`: active after CHAT-00 through CHAT-04; owns MVP listing
  conversations, messages, and read state in its own `chat` schema.
- `common-core`: shared IDs, money, time, validation, and other stable value
  helpers.
- `common-web`: shared web error envelope, correlation, API validation, and
  current actor support.
- `common-storage`: shared technical object-storage adapters for listing media
  and avatars; business rules stay in the owning services.
- `common-testing`: shared test support.

## Active MVP Angular Route Groups

- Public marketplace: `/`, `/marketplace`, `/listings/:listingId`
- Business store browse: `/stores`
- Marketplace account: `/account/profile`, `/account/listings`,
  `/account/listings/new`, `/account/listings/:listingId/edit`
- Business seller portal foundation: `/seller`
- Admin portal: `/admin/business-applications`,
  `/admin/listings/moderation`

Individual seller tools belong under marketplace account routes. Business
seller operational tools belong under `/seller`. Admin review and moderation
belong under `/admin`.

## Deferred V2 Or Tutorial Backend Modules

These modules may remain in the repository, but they are not active MVP paths
for the current search work:

- `inventory-service`
- `order-service`
- `payment-service`
- `notification-service`

Do not build new MVP search behavior on those services. Cart, inventory,
checkout/payment, orders/shipping, and notifications remain V2.

## Deferred Or Archived Angular Screens

These feature folders are historical/tutorial or V2-facing unless a future
roadmap slice explicitly reactivates them:

- `frontend/src/app/features/inventory/`
- `frontend/src/app/features/orders/`
- `frontend/src/app/features/payments/`
- Legacy generic product screens under `frontend/src/app/features/products/`

Current MVP product/listing work is in:

- `frontend/src/app/features/marketplace/`
- `frontend/src/app/features/stores/`
- `frontend/src/app/features/listings/`
- `frontend/src/app/features/seller/`
- `frontend/src/app/features/business/`

## Search Work Boundary

Completed search slices build on:

- Public listing read APIs in `product-service`
- Marketplace and store public Angular surfaces
- The current listing/media moderation rules
- OpenSearch as a derived projection only, with MySQL remaining authoritative

Search work should not introduce cart, order, payment, inventory, shipping,
notification, reputation, review, or AI behavior.

## Related Docs

- `docs/mvp/site/site-00-logical-three-site-separation.md`
- `docs/mvp/site/site-01-public-ui-surface-split.md`
- `docs/mvp/search/search-00-search-storefront-read-model-plan.md`
- `docs/mvp/search/search-01-public-approved-listing-browse.md`
- `docs/mvp/search/search-02a-individual-marketplace-keyword-search.md`
- `docs/mvp/search/search-01b-business-storefront-search.md`
- `docs/mvp/search/search-03-shared-cursor-pagination.md`
- `docs/mvp/search/search-04-opensearch-projection.md`
