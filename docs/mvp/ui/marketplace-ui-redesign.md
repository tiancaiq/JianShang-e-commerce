# Marketplace UI Redesign Direction

Status: Approved direction

Scope: Public marketplace/general user site

Related surfaces:

- Public marketplace site: shopping and discovery UI
- Business seller portal: management dashboard UI
- Admin portal: management dashboard UI

## Purpose

This document turns the four reviewed marketplace mockups into a concrete
direction for the public marketplace UI.

The public marketplace should feel like a friendly shopping site with strong
search, category browsing, product cards, and listing detail pages. The seller
portal and admin portal should not copy this shopping layout; they stay
dashboard-oriented.

## Mockup Analysis

### Option 1: Top-left pink marketplace

Strengths:

- Strong brand personality.
- Clear hero area and featured items.
- Left category navigation is easy to understand.
- Product cards are visually attractive.

Risks:

- Very pink and decorative, which may feel too narrow for a general
  marketplace.
- Hero image dominates the first screen.
- The layout feels more like a themed collectible shop than a broad
  marketplace.

Use:

- Keep the friendly tone, rounded cards, soft badges, and clear category rail.
- Do not copy the full intensity of the pink palette.

### Option 2: Top-right balanced marketplace

Strengths:

- Best balance between playful style and marketplace usability.
- Search, category dropdown, wishlist, notifications, cart, and user avatar
  are easy to scan.
- Listing grid is dense enough for browsing.
- Tabs, sort, and filter controls support real shopping behavior.

Risks:

- Still has a strong anime/collector niche look.
- Left sidebar plus top filters can become busy on smaller screens.

Use:

- Use this as the primary structure reference for browse/search pages.
- Keep the tabs, filter, sort, card grid, and compact header behavior.

### Option 3: Bottom-left clean commerce homepage

Strengths:

- Most suitable for the public homepage.
- Header and search feel familiar to users.
- Hero is broad but not too dashboard-like.
- Trust/value cards explain marketplace safety and community.
- Category sidebar and trending section give a clear shopping path.

Risks:

- Trust cards mention payments; MVP must not imply platform payment or buyer
  protection for individual trades.
- Still needs clearer seller type labels on listing cards.

Use:

- Use this as the primary homepage reference.
- Replace "Easy Payments" style messaging with accurate MVP safety notices.

### Option 4: Bottom-right dashboard marketplace hybrid

Strengths:

- Strong signed-in user area.
- Useful for seller dashboard, wallet, activity, and account panels later.
- Dark sidebar provides a strong management-system feel.

Risks:

- Too dashboard-heavy for guests.
- Right profile panel distracts from public browsing.
- Wallet/orders concepts are V2 and should not be central in MVP.

Use:

- Do not use this for the public marketplace homepage.
- Reuse ideas later for seller portal or signed-in account dashboard, not
  guest browsing.

## Recommended Direction

Use a hybrid of Option 3 and Option 2:

- Homepage structure from Option 3.
- Browse/search controls from Option 2.
- Soft visual personality from Option 1.
- Dashboard/account patterns from Option 4 only for seller/admin/account areas.

The marketplace should be:

- Shopping-first.
- Search-first.
- Guest-friendly.
- Trustworthy and not overly decorative.
- Clear about individual versus business sellers.
- Clear that individual payment and delivery are off-platform.

## Marketplace Homepage Layout

Desktop order:

1. Sticky top header.
2. Left category rail on desktop.
3. Hero banner.
4. Safety/value strip.
5. Featured or newest approved listings.
6. Category sections or curated rows.
7. Footer with policy, contact, and marketplace safety links.

### Header

Required elements:

- Logo.
- Large central search input.
- Category selector or category shortcut.
- Browse/search entry.
- Sign in/profile entry.
- Sell/list item entry for authenticated individual sellers.
- My Listings/account entry for signed-in individual sellers.
- Wishlist/messages may appear later when the flows exist.

Do not show V2 cart/order/payment navigation as active MVP features.

### Category Rail

Desktop:

- Left rail can show top categories.
- Keep labels short.
- Include "All categories" and common marketplace groups.

Mobile:

- Use a drawer or horizontal category chips.

### Hero

Purpose:

- Introduce the marketplace.
- Explain the buyer/seller value.
- Lead to browse or list actions.

Hero copy should avoid payment protection claims.

Approved example tone:

```text
Find it. List it. Trade safely.
Browse individual listings in the marketplace, with business stores separated.
```

Call to action examples:

- `Browse Listings`
- `List an Item`
- `Start Selling`

### Safety/Value Strip

Use small cards under the hero, but keep claims accurate.

Allowed MVP cards:

- `Verified Accounts`
- `Business Review`
- `Seller Type Labels`
- `Off-Platform Trade Notice`

Avoid:

- `Secure Payments`
- `Buyer Protection`
- `Guaranteed Delivery`
- Any claim that the platform verifies individual off-platform payment or
  delivery.

## Listing Card Rules

Every public listing card should show:

- Image.
- Title.
- Price and currency.
- Seller type badge: `Individual` or `Business`.
- Condition.
- Approximate location when relevant.
- Favorite button only if implemented.

Individual listing cards should show:

- `Payment arranged with seller`
- City/region only, never exact address.

Business listing cards should show:

- Business/store name when available.
- No cart or checkout button in MVP.

Primary card action:

- `View Details`

Do not add `Buy Now`, `Add to Cart`, or platform-payment actions until V2.

## Browse/Search Page Layout

Use Option 2 as the structure reference.

Required elements:

- Search term display.
- Category filter.
- Seller type filter.
- Condition filter.
- Price range filter.
- Sort dropdown.
- Responsive listing grid.
- Empty state with helpful recovery action.

Initial MVP search can be database-backed. OpenSearch should wait until the
database-backed public browse path is correct.

## Listing Detail Page

Required layout:

- Large image gallery.
- Title and price.
- Seller type badge.
- Seller summary.
- Condition and category.
- Description.
- Approximate location.
- Contact/message action for signed-in users.
- Sign-in prompt for guests who want to message.

Individual listings must show a visible notice:

```text
Payment and delivery are arranged directly between buyer and seller. The
platform does not process or verify off-platform payment.
```

Business listings in MVP must show:

```text
Business checkout is not available yet.
```

## Visual Style

The public marketplace can use soft, friendly styling, but it should not feel
like a niche-only anime shop.

Guidelines:

- Use a light background.
- Use one strong brand accent plus soft secondary colors.
- Keep cards rounded and image-forward.
- Use playful badges sparingly.
- Keep text readable and high contrast.
- Avoid overloading the screen with icons, sparkles, or decorative elements.

The site may support themed imagery later, but the MVP UI should be general
enough for used items, collectibles, local goods, and business products.

## Individual Seller Account Area

Individual sellers manage personal listings inside the marketplace site, not
inside the business seller portal.

Required marketplace account links:

- `Sell`
- `My Listings`
- `Seller Profile`

The account/listing pages may use more form-focused layouts than public browse
pages, but they should remain visually connected to the marketplace site. Do
not send individual sellers to the business portal unless they are managing an
approved business.

## Responsive Rules

Desktop:

- Header search is prominent.
- Left category rail may be visible.
- Listing grid uses 4 or more columns when space allows.

Tablet:

- Category rail can collapse.
- Listing grid uses 2 or 3 columns.

Mobile:

- Search remains near the top.
- Category filters use chips/drawer.
- Listing cards use 1 or 2 columns depending width.
- Sticky bottom navigation is allowed only if it does not hide critical
  actions.

## Do Not Use In MVP

- Platform cart for business listings.
- Checkout/payment buttons.
- Order tracking entry points.
- Wallet panels.
- Seller earnings panels.
- Claims that individual trades are payment-protected.
- Exact individual seller addresses.

## Acceptance Criteria For UI Work

- Guests can understand what the marketplace sells without logging in.
- Guests can browse approved listings without seeing management dashboards.
- Listing cards clearly distinguish individual and business sellers.
- Individual listings show off-platform payment/delivery responsibility.
- Seller/admin dashboard UI remains separate from marketplace browsing UI.
- The marketplace UI is responsive and keyboard-accessible.
