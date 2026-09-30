# DEV-CATALOG-SEED-01 large marketplace development catalog

Status: implemented as an opt-in local/development fixture.

## Purpose and boundaries

This slice provides a deterministic, service-owned import of 10,000 active
marketplace listings for realistic browse, search, storefront, and Marketplace
Agent testing. It uses item metadata from
`McAuley-Lab/Amazon-Reviews-2023`; it never reads review text, ratings, reviewer
identities, or review timestamps.

The fixture preserves the approved product boundaries:

- Product Service remains authoritative for categories and listings.
- Auth Service remains authoritative for users, individual seller profiles,
  businesses, memberships, and stores.
- OpenSearch is populated only through Product Service projection work.
- Individual listings remain off-platform trades. Seed descriptions never
  claim payment verification or buyer protection.
- Business listing `quantity` is MVP display/catalog information, not V2
  inventory availability.
- Seed-only internal endpoints are disabled unless
  `SEED_LARGE_CATALOG=true`, require the existing internal commerce token, and
  reject `prod` and `production` Spring profiles.
- The optional V2 inventory endpoint is separately disabled unless
  `SEED_LARGE_CATALOG_INVENTORY=true`; running the catalog seed alone never
  creates authoritative stock.

## Default shape

The defaults create:

- 10,000 active approved listings;
- 4,000 individual listings across 1,200 active individual sellers;
- 6,000 business listings across 175 approved businesses and active stores;
- deterministic IDs derived from the seed namespace and source identity;
- a weighted 18-month listing-age distribution;
- deterministic long-tail views and likes, including zero-traffic listings and
  rare viral outliers, with likes never exceeding views;
- category-aware fallback prices when source metadata has no usable price;
- individual condition, negotiability, and single-quantity behavior;
- coherent business store segments, SKU values, and positive display quantity.

The Product migrations initially add source-oriented leaves, then consolidate
them into 13 shopper-facing categories. Electronics, home, clothing, and media
products use their broad native categories; only durable discovery facets such
as automotive, sports, pets, toys, musical instruments, office and crafts,
beauty, and baby remain separate. Retired leaves are deprecated with explicit
replacement categories, and existing listings are migrated forward.

## Source and transformation policy

The importer streams only these repository paths:

```text
raw/meta_categories/meta_<domain>.jsonl
```

The default domain set covers electronics, phones, home, office, sports, toys,
pets, musical instruments, clothing, beauty, books, games, automotive, tools,
crafts, and appliances. It inspects 25,000 candidates by default and selects
10,000 accepted products after a stable shuffle.

Rows are rejected when they lack a usable source identity/title or meaningful
feature/description content, represent a digital/subscription item, or cannot
map to an active creation-enabled shopper category. Amazon-specific promotional phrases
are removed. Source prices are bounded; missing/invalid prices use a
deterministic logarithmic sample from category-specific ranges.

Source image URLs are not persisted by default. Product media requires an
owned storage object and completed upload lifecycle, so the importer does not
create external URL references or fake media rows. Setting
`SEED_USE_SOURCE_IMAGES=true` currently records a warning and does not bypass
that ownership rule.

## Runbook

Start the demo services with the seed overlay so the protected endpoints and
OpenSearch projection worker are enabled:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.large-catalog-seed.yml up -d --build auth-service product-service
```

Run the seed from the repository root:

```powershell
.\scripts\seed-marketplace-catalog.ps1 seed
```

`reseed` is an idempotency alias. The same namespace and source data update the
same stable records rather than creating duplicates:

```powershell
.\scripts\seed-marketplace-catalog.ps1 reseed
```

Verify counts, integrity, public search, and Product's actual internal Agent
retrieval contract:

```powershell
.\scripts\seed-marketplace-catalog.ps1 verify
```

When the V2 cart runtime is also running, explicitly initialize the seeded
business listings as purchasable local-demo inventory:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml -f docker-compose.large-catalog-seed.yml up -d inventory-service
.\scripts\seed-marketplace-catalog.ps1 inventory
```

This command reads only Product-registered business seed candidates and asks
Inventory Service to initialize missing stock through a token-protected,
non-production fixture contract. Existing Inventory balances are preserved,
so rerunning it cannot replenish stock consumed by purchases or seller
adjustments.

Do not run `reset` after the inventory extension has been used. Inventory
movements and reservation history are durable V2 evidence and this slice does
not delete them or cascade Product/Auth fixture cleanup across service-owned
schemas. Reuse the stable namespace with `reseed` instead. A coordinated
cross-service fixture-retirement command remains deferred.

Remove only fixture-owned rows:

```powershell
.\scripts\seed-marketplace-catalog.ps1 reset
```

Reset deletes Product rows registered in `large_catalog_seed_listings`, queues
their OpenSearch deletes, and then deletes Auth rows registered in
`large_catalog_seed_entities`. Manual and unrelated developer rows are not
selected by either reset.

The verification report is written to
`build/reports/large-catalog-seed.json` after a successful seed.

## Configuration

| Variable | Default | Meaning |
|---|---:|---|
| `SEED_NAMESPACE` | `amazon-reviews-2023-v1` | Stable ownership and identity namespace |
| `SEED_TOTAL_LISTINGS` | `10000` | Required total |
| `SEED_INDIVIDUAL_LISTINGS` | `4000` | Individual share |
| `SEED_BUSINESS_LISTINGS` | `6000` | Business share |
| `SEED_INDIVIDUAL_SELLERS` | `1200` | Individual seller count |
| `SEED_BUSINESS_SELLERS` | `175` | Business/store count |
| `SEED_CANDIDATE_PRODUCTS` | `25000` | Metadata rows inspected |
| `SEED_RANDOM_SEED` | `20260928` | Deterministic transformation seed |
| `SEED_MAX_AGE_DAYS` | `548` | Maximum listing age |
| `SEED_BATCH_SIZE` | `500` | Product Service request batch size |
| `SEED_SOURCE_DOMAINS` | documented default set | Comma-separated metadata domains |
| `SEED_USE_SOURCE_IMAGES` | `false` | Reserved owned-media opt-in; external URLs remain disabled |

For zero-network tests, `seed` and `reseed` also accept
`--source-jsonl <path>`. Each local metadata row must include
`source_domain` and otherwise use the upstream item-metadata shape.

## Verification criteria

A successful run requires:

- exact 4,000/6,000 individual/business active counts;
- exact seller/store counts and no duplicate source identities;
- no future timestamps, invalid timestamp ordering, nonpositive prices, or
  invalid quantity;
- zero pending projection work before search verification;
- nonempty public and internal Agent retrieval results for the documented
  example queries;
- a second run that remains at 10,000 listings;
- reset that leaves the pre-existing non-seed listing count unchanged.

The script exercises searches including wireless keyboard, gaming monitor,
used iPhone, pink dress, office chair, running shoes, USB-C charger, dog toy,
guitar, desk, and wireless headphones.
