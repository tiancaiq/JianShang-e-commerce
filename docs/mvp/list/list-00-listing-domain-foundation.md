# LIST-00 Listing Domain Foundation

## Scope

LIST-00 prepares the catalog/listing domain schema without enabling listing
creation, listing browsing, media upload, search, moderation, or frontend
listing workflows.

This slice is intentionally schema-first.

## Ownership Model

Listings share one table and use `seller_type` to select the owner shape.

Individual listing ownership:

- `seller_type = INDIVIDUAL`
- `individual_seller_user_id` is required
- `business_id` and `store_id` are null
- `quantity >= 1`
- approximate public city/region is required

Business listing ownership:

- `seller_type = BUSINESS`
- `business_id` is required
- `individual_seller_user_id` is null
- `store_id` may be populated by later store slices
- `negotiable = false`
- `quantity >= 0`

The listing service stores owner IDs but does not query another service's
database. Later command handlers must validate identity seller status or
business membership through service APIs.

## Tables

The migration creates:

- `categories`
- `category_attribute_definitions`
- `listings`
- `listing_attributes`

No seed categories are inserted yet. `LST-01` should define the first active
category hierarchy and read API.

## Non-goals

- No listing API endpoints.
- No listing creation forms.
- No media tables or upload flow.
- No listing moderation cases.
- No search index or storefront projections.
- No frontend marketplace/listing UI.

## Verification

Run:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl product-service -am test
```
