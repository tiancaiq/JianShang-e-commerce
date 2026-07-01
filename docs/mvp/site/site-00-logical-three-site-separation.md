# SITE-00 Logical Three-Site Separation

Status: complete.

## Scope

SITE-00 keeps one Angular application while separating the user experience into
three logical sites:

- Marketplace public/user site
- Business seller portal
- Admin portal

This slice does not add inventory, orders, payments, checkout, or new backend
admin role behavior.

## Route Groups

```text
/                 marketplace public/user site
/seller           business seller portal
/admin            admin portal
```

MVP target:

```text
/sell                         marketplace individual seller entry
/account/listings             marketplace individual seller listing management
/account/listings/new         marketplace individual listing creation
/account/listings/:id/edit    marketplace individual listing editing
```

Compatibility redirects remain for older seller paths until IND-03 is
implemented:

```text
/seller/activate              -> /account/seller-profile
/seller/listings              -> /account/listings
/seller/listings/new          -> /account/listings/new
/seller/listings/:id/edit     -> /account/listings/:id/edit
/business/apply               -> /seller/business/apply
/profile                      -> /account/profile
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
- Navigation has marketplace, stores, sell, account/login state.
- Individual seller activation and personal listing management live here.
- Admin links are not shown.

Seller layout:

- Protected by BFF session guard.
- Business seller navigation only.
- No individual personal listing tools.
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
