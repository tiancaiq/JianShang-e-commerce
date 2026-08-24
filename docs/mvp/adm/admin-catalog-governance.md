# ADM-CAT-01/02 catalog and marketplace governance

## Status and ownership

Product Service remains authoritative for the category hierarchy, attributes,
seller guidance, listing eligibility, and listing validation. Auth Service owns
the canonical admin permissions and role mappings. The original category seed
is bootstrap data only; runtime reads and writes use Product's database.

The existing `categories`, `category_attribute_definitions`,
`listing_attributes`, and `listings.category_id` structures were extended,
not duplicated.

## Category lifecycle and hierarchy

Categories use `ACTIVE`, `DISABLED`, or `DEPRECATED` and have an immutable ID
and slug, optional parent/replacement, description, display order, seller
eligibility, explicit creation/submission switches, optimistic `version`, and
`current_rule_version`.

- Active categories may accept drafts/submissions when their switches and
  seller eligibility allow them.
- Disabled/deprecated categories accept neither new drafts nor submissions.
- Lifecycle changes preserve the explicit creation/submission switches; an
  administrator must review policy separately when re-enabling a category.
- Disabling/deprecating never deletes, closes, hides, migrates, or rewrites an
  existing listing.
- Replacement is advisory; no listing category ID is rewritten.
- Parent moves validate existence, self-parenting, descendant cycles, and a
  three-level maximum.

Eligibility is `INDIVIDUAL`, `BUSINESS`, `BOTH`, or `NONE`. Product enforces it
on draft create/update and again on submission/business publication.

## Attributes and validation

Managed types are `TEXT`, `NUMBER`, `BOOLEAN`, `ENUM`, and `MULTI_ENUM`.
Attribute keys and enum values are stable. Labels, descriptions,
required/searchable/filterable flags, order, safe validation, and lifecycle
are versioned. Historical attributes/options are disabled, never hard-deleted.

Seller values remain in `listing_attributes`; multi-enum uses a bounded JSON
array in the existing string slot. Product validates types, text length,
number bounds, active enum values, required fields, category availability, and
seller eligibility. No regex, script, or executable policy language exists.

Searchable/filterable flags are persisted, but OpenSearch facets/reindexing are
deferred to system/search operations. ADM-SYS now exposes bounded listing
projection visibility, failed-work retry, and one-listing reindex. The separate
full Search Maintenance workflow remains feature-gated and disabled by default;
see [admin-system-operations.md](admin-system-operations.md).

## Rule versions and compatibility

Every governed validation change publishes an immutable
`category_rule_versions` row and advances `current_rule_version`. Drafts store
`listings.category_rule_version` and send the editor's schema version.

- New drafts use the latest rule.
- Existing drafts use latest rules on next save; stale editors receive
  `CATEGORY_RULE_CHANGED` and preserve compatible values after schema reload.
- Submission/business publication enforces captured=current and all required
  values.
- Pending moderation is not revalidated by later catalog changes and remains
  pinned to the version captured when submission succeeded.
- Active listings are grandfathered until a meaningful seller edit/resubmit.

Owner-scoped `GET /api/v1/listings/{listingId}/catalog-values` reloads the
captured version and dynamic values without expanding public listing queries.

## Seller guidance reconciliation

ADM-CAT seller guidance is separate Product configuration with `TIP`,
`WARNING`, `BEST_PRACTICE`, and `POLICY_NOTICE`. Active guidance appears in
the listing editor and never participates in validation.

The earlier Category Guidance feature is an immutable, feature-gated AI/RAG
knowledge-publication stream. It is preserved and remains disabled by default;
it was not silently enabled or repurposed.

## Dry run and no-hidden-cascade guarantee

Status, hierarchy, policy, new attributes, required-rule changes, and enum
option lifecycle changes expose impact previews with exact category counts,
missing-required-value count where applicable, and the category/rule versions
used. The admin detail workbench previews these changes before enabling the
confirmation command. Catalog commands never:

- delete or change an existing listing status;
- rewrite historical listing/enum values;
- create moderation or enforcement actions;
- restrict a user or business;
- cancel or mutate an order, payment, refund, or dispute.

## Authorization, concurrency, idempotency, audit

- `admin.catalog.read`: hierarchy/detail/rules/audit.
- `admin.catalog.category.manage`: create/edit/move category metadata.
- `admin.catalog.attribute.manage`: manage attributes/options.
- `admin.catalog.policy.manage`: lifecycle, eligibility, required rules, rule
  publication, and seller guidance.

`SUPER_ADMIN`, `PLATFORM_ADMIN`, and `CATALOG_ADMIN` receive all four;
`AUDITOR` is read-only; listing moderators receive none by default. Attribute
rule changes require attribute and policy permissions.

All mutable aggregates use expected versions and stale writes return `409`.
Category creation/status and attribute creation use actor/operation-scoped
request-hash idempotency. `catalog_events` records the server-derived human
admin, target, before/after state, reason, correlation/request IDs, time, and
safe metadata. Failed validation and page views are not successful history.

## Routes and deferred work

Admin UI routes are `/admin/catalog`, `/admin/catalog/categories`, and
`/admin/catalog/categories/:categoryId`. Protected admin APIs live below
`/api/v1/admin/catalog/categories`; status, move, policy, attribute creation or
required-status edits, and enum-option lifecycle have `/dry-run` companions.
`GET /api/v1/categories` is the active seller schema.

The focused MySQL policy integration suite covers every supported attribute
type, persistence, required values, stale rule versions, enum lifecycle,
seller eligibility, and active-listing compatibility. Angular service,
capability, listing-editor, and helper tests plus the route-mocked catalog
Playwright workflow cover impact confirmation without modifying live data.

Deferred: OpenSearch facets/backfill, automatic category migration, a separate
draft-rule authoring workspace, arbitrary policy languages, and all AI.

## ADM-GOV sensitive-action integration

Product's existing status dry run remains the sole source of category impact.
When disabling a category with at least the Auth policy's configured active-
listing threshold (seeded as `1000`), the public command returns HTTP `202`
with an approval reference and does not mutate the category. After an
independent authorized reviewer approves it, Auth revalidates Product's current
category version and counts and invokes the typed internal status command.
Ordinary metadata/status changes below the threshold remain direct. Active
listings are never changed by category disablement. See
[admin-governance.md](admin-governance.md).
