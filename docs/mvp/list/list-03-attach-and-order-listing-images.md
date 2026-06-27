# LIST-03 Attach and Order Listing Images

Status: complete.

## Scope

LIST-03 attaches confirmed media metadata to a draft listing as an ordered image
list.

This slice does not upload file bytes, render public listing pages, submit
listings for moderation, or publish/search listings.

## Backend

Implemented endpoint:

```text
PUT /api/v1/listings/{listingId}/images
```

Request:

```json
{
  "images": [
    {
      "mediaId": "01M00000000000000000000001",
      "altText": "Front view"
    }
  ]
}
```

Rules:

- Listing must exist and be in `DRAFT`.
- Only the owning individual seller or authorized business member can update
  image order.
- Referenced media must belong to the same listing.
- Referenced media must have `uploadStatus = UPLOADED`.
- Duplicate media IDs are rejected.
- Request array order becomes `displayOrder`.
- Image moderation starts as `NOT_SUBMITTED`.

## Persistence

Added Flyway migration:

```text
product-service/src/main/resources/db/migration/catalog/V202606170300__create_listing_images.sql
```

The migration creates `listing_images`, which references `listings` and
`listing_media_objects` and stores display order, alt text, moderation status,
version, and timestamps.

## Frontend

The draft listing form now attaches the confirmed media to the listing image
list after the media confirmation step. The visible image list reflects the
attached listing images and their order.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Real object storage upload.
- Image previews from public or signed object URLs.
- Drag/drop reordering.
- Listing submit/publish/moderation.
- Public listing and storefront image rendering.
