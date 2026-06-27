# SITE-00 Logical Three-Site Separation

Status: complete.

## Scope

SITE-00 keeps one Angular application while separating the user experience into
three logical sites:

- Marketplace public/user site
- Seller portal
- Admin portal

This slice does not add inventory, orders, payments, checkout, or new backend
admin role behavior.

## Route Groups

```text
/                 marketplace public/user site
/seller           business and individual seller portal
/admin            admin portal
```

Compatibility redirects remain for older seller paths:

```text
/dashboard        -> /seller/dashboard
/listings/new     -> /seller/listings/new
/business/apply   -> /seller/business/apply
/profile          -> /seller/profile
```

## Layouts

Added separate Angular layouts:

```text
MarketplaceLayoutComponent
SellerLayoutComponent
AdminLayoutComponent
```

Marketplace layout:

- Public route group.
- Guest browsing is allowed.
- Navigation has Browse, Sell, Account/Login state.
- Admin links are not shown.

Seller layout:

- Protected by BFF session guard.
- Seller navigation only.
- No inventory, orders, or payment links.

Admin layout:

- Protected by BFF session guard.
- Admin navigation only.

## Security Boundary

Frontend route protection uses the existing BFF session guard. Backend services
remain the authorization source of truth for protected commands and admin
decisions.

No access or refresh token is stored in the browser.

## Verification

Expected local checks:

```powershell
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```
