# LIST Frontend Stabilization

Status: complete.

## Scope

This stabilization keeps the implemented LIST frontend behavior intact while
reducing duplication and making image/display rules explicit before more
SEARCH and storefront work is added.

No backend API, database, listing lifecycle, inventory, checkout, payment,
order, or chat behavior was added.

## UI Behavior Contract

Public marketplace browse:

- Public listing cards show the first approved listing image only.
- Public listing cards do not auto-rotate images.
- Public listing cards do not place seller-type badges over the image.
- Seller type remains visible in listing metadata, not as image-blocking
  decoration.

Public listing detail:

- The detail page uses a reusable listing image gallery.
- The gallery shows a large selected image.
- When multiple approved images exist, dark previous/next arrow buttons change
  the selected image.
- When multiple approved images exist, thumbnails can select the large image.
- The selected large image auto-advances every five seconds.
- With no public images, the gallery shows a placeholder.

Seller listing draft/edit:

- Marketplace account listing routes remain:
  - `/account/listings`
  - `/account/listings/new`
  - `/account/listings/:listingId/edit`
- Individual seller drafts support up to 10 images.
- Selected images preview immediately before save.
- Attached images can be removed.
- Invalid required fields are highlighted after submit/save attempt.
- `DRAFT` listings can submit for review when required fields and at least one
  attached image are present.
- `PENDING_REVIEW`, `ACTIVE`, and `CLOSED` listings can be edited, but submit
  for review is disabled until the seller changes listing fields.
- `CLOSED` listings do not show another close action; after edit/save they can
  return to review and become public only after admin approval.
- Pending local image uploads block submit for review until saved.

## Frontend Structure

Added shared UI:

```text
frontend/src/app/shared/components/ui/listing-image-gallery.component.ts
```

Added shared test fixtures:

```text
frontend/src/app/testing/listing-test-fixtures.ts
```

Moved submit/review eligibility rules into:

```text
frontend/src/app/features/listings/listing-draft-form.helpers.ts
```

## Verification

Expected local checks:

```powershell
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Moving large marketplace component styles into external stylesheet files.
- Extracting a reusable public listing card component.
- Full visual regression automation.
- SEARCH pagination, richer filters, storefront pages, and OpenSearch
  projection.
