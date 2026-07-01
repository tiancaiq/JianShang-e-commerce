# MEDIA-01 Object Storage Image Delivery

Status: complete.

## Scope

MEDIA-01 connects listing images to S3-compatible object storage. The MVP
configuration targets Google Cloud Storage through its S3-compatible XML API
using HMAC credentials and bucket `jianshang`.

The storage bucket should remain private. Browsers receive application media
URLs, and product-service reads approved media from object storage. No
bucket-wide public access is required.

## Backend

Implemented behavior:

- `POST /api/v1/listings/{listingId}/media/upload-request` now creates a
  storage object key and returns a signed `PUT` upload URL.
- `POST /api/v1/listings/{listingId}/media/{mediaId}/confirm` verifies the
  object exists in storage and matches the requested size before marking media
  as `UPLOADED`.
- `GET /api/v1/listings/{listingId}/media/{mediaId}/content` returns a signed
  read redirect for the owning seller preview.
- `GET /api/v1/public/listing-media/{imageId}` reads approved images attached
  to approved active public listings through product-service.

Rules:

- Image content types remain JPEG, PNG, and WebP.
- Image size remains 10 MB or less by default.
- Public media access requires listing, listing image, and media object
  moderation to be approved.
- Seller preview access still checks listing ownership or business listing
  permission.

## Configuration

Environment-backed product-service properties:

```text
LISTING_MEDIA_STORAGE=s3
S3_ENDPOINT_URL=https://storage.googleapis.com
S3_REGION=auto
S3_BUCKET=jianshang
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
S3_PATH_STYLE_ACCESS=true
LISTING_MEDIA_SIGNED_URL_TTL=PT15M
```

Local browser testing needs bucket CORS similar to:

```json
[
  {
    "origin": ["http://localhost:4200"],
    "method": ["GET", "HEAD", "PUT"],
    "responseHeader": ["Content-Type"],
    "maxAgeSeconds": 3600
  }
]
```

Local demo mode remains available with `LISTING_MEDIA_STORAGE=local-demo` for
offline tests and metadata-only development.

## Frontend

The draft listing form now uploads selected image bytes to the signed storage
URL before confirming metadata. Seller draft image lists, public browse cards,
and public listing detail pages render actual images through gateway media
URLs.

Direct storage uploads do not use gateway cookies, CSRF headers, browser-stored
tokens, access tokens, or refresh tokens.

## Verification

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

Manual browser check:

1. Start Keycloak, MySQL, auth-service, product-service, api-gateway, and the
   Angular dev server.
2. Sign in as an activated individual seller.
3. Create or edit a draft listing and select an image before saving.
4. Confirm the image appears in the draft image list.
5. Submit the listing, approve it in admin moderation, then open the public
   marketplace and listing detail page.
6. Confirm the approved image renders for guests.
