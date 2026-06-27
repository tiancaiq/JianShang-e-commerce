# LIST-02 Media Upload Request and Confirm Metadata

Status: complete.

## Scope

LIST-02 lets a seller reserve and confirm media metadata for an existing draft
listing.

This slice does not publish listings, expose public media, order images, submit
listings for moderation, or integrate real object storage. The local
implementation returns a demo upload URL and stores metadata only.

## Backend

Implemented endpoints:

```text
POST /api/v1/listings/{listingId}/media/upload-request
POST /api/v1/listings/{listingId}/media/{mediaId}/confirm
```

Rules:

- Listing must exist and be in `DRAFT`.
- Individual listing media requires the active individual seller profile that
  owns the listing.
- Business listing media requires business listing permission for the owning
  business.
- Only JPEG, PNG, and WebP images are accepted.
- Image size must be 10 MB or less.
- Upload request creates a `PENDING_UPLOAD` media row.
- Confirm changes the row to `UPLOADED` and keeps moderation as
  `NOT_SUBMITTED`.
- Client-supplied owner, status, and moderation values are not accepted.

## Persistence

Added Flyway migration:

```text
product-service/src/main/resources/db/migration/catalog/V202606170200__create_listing_media_objects.sql
```

The migration creates `listing_media_objects` owned by product-service catalog
schema. Rows store listing ownership snapshot, object key, file metadata,
upload status, moderation status, version, and timestamps.

## Frontend

The draft listing form includes an image selector before save. The browser
keeps the selected file locally until the draft is created, then requests a
media slot and confirms metadata through the gateway BFF session. No browser
token storage is used.

## Verification

Expected local checks:

```powershell
mvn.cmd -pl product-service -am -DskipTests compile
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

Run product-service integration tests in a shell where Docker/Testcontainers is
available.

## Deferred

- Real object storage upload.
- Display order and alt text.
- Listing submit/publish.
- Listing moderation.
- Public listing and storefront media rendering.
