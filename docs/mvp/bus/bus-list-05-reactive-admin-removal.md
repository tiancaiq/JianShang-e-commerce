# BUS-LIST-05 Reactive Admin Removal

Status: complete.

## Goal

Let a platform admin reactively remove an active business self-published item
without deleting its catalog or moderation history. The seller must be able to
see the recorded reason after removal.

## Contract

```text
POST /api/v1/admin/listings/{listingId}/remove
If-Match: {listingVersion}
```

```json
{
  "reason": "Business item violates marketplace policy"
}
```

## Rules

- Requires `PLATFORM_ADMIN`.
- Accepts an active approved individual listing or an active business listing
  with `publicationSource=BUSINESS_SELF_PUBLISHED`.
- Requires the current listing version and a nonblank reason.
- Changes the listing status to `REMOVED_BY_ADMIN` and immediately removes it
  from public detail and search results.
- Appends an immutable `ADMIN_REMOVE` moderation decision with actor, reason,
  and timestamp.
- Does not delete the listing, media, or prior moderation history.
- Seller item reads and the business management list expose the latest removal
  reason and timestamp.
- Removed items have no seller lifecycle actions in MVP. Reinstatement remains
  an admin-owned follow-up rather than a seller relist action.

## Verification

- Product-service integration coverage verifies business self-published
  removal, public `404`, audit persistence, and seller reason visibility.
- Seller component coverage verifies the removed status and reason render with
  no edit, publish, pause, or relist action.

## Deferred

- Admin removal queue and discovery UX.
- Reinstatement or appeal workflow.
- Automated moderation and notifications.
