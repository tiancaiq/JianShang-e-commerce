# USER-02 Safe Public Identity Labels

Status: complete.

## Goal

Provide a small auth-service-owned read contract for marketplace-safe seller
labels so listing and future chat surfaces can show understandable names
without reading identity tables directly or exposing private account data.

## Scope

- Add the public label alias `GET /api/v1/users/public-labels`.
- Preserve the existing `GET /api/v1/public/seller-labels` route used by
  product-service.
- Return only safe label fields:
  - user `id`
  - user `displayName`
  - optional user `avatarUrl`
  - business `id`
  - business `legalName`
- Filter public labels to active users and active businesses.
- Cap each requested ID set to 50 IDs.
- Make public listing enrichment fall back to neutral labels when auth-service
  returns no label or cannot be reached.

## Non-Goals

- No public profile page.
- No chat implementation.
- No likes, reviews, notifications, address book, or reputation.
- No email, phone, role, account status, Keycloak subject, or verification
  exposure.
- No database migration.

## API Contract

```text
GET /api/v1/users/public-labels?userIds=01J...&businessIds=01J...
GET /api/v1/public/seller-labels?userIds=01J...&businessIds=01J...
```

Response:

```json
{
  "data": {
    "users": [
      {
        "id": "01J...",
        "displayName": "Alex Seller",
        "avatarUrl": "https://example.com/avatar.png"
      }
    ],
    "businesses": [
      {
        "id": "01J...",
        "legalName": "MSB Local Store LLC"
      }
    ]
  }
}
```

Public callers receive no rows for missing, suspended, or closed identities.
Product-service converts missing public labels to neutral display labels such
as `Marketplace seller` or `Business seller`.

## Tests

- Auth-service verifies guest access, optional `avatarUrl`, private-field
  omission, and hidden suspended users.
- Product-service verifies public listing browse includes safe display label
  and optional seller avatar URL while omitting raw seller IDs.
