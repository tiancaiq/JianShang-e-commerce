# V2-CART-REL-P0-02B Cart CI Gate Definition

Status: defined locally. This is CI plumbing only and does not publish a cart
candidate or advance the business counter.

Release: V2.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-cart-03a-cart-only-capability-boundary-business-entry.md`
- `docs/v2/commerce/v2-cart-03b-amazon-style-cart-page-validation-repair-ux.md`
- `docs/v2/commerce/v2-cart-03c-cart-integration-header-feedback-local-acceptance.md`
- `docs/v2/commerce/v2-cart-03d-product-owned-store-provenance-projection-cart-dto.md`

## Goal

Define a clean-host GitHub Actions gate that can run against an exact cart
candidate SHA and prove the store-only cart source boundary before runtime or
browser acceptance.

## Workflow

`Cart Release Quality` lives in
`.github/workflows/cart-release-quality.yml`.

Triggers:

- Pull requests to `dev`, `main`, or `master`.
- Manual `workflow_dispatch`.

The workflow checks out the event head SHA, uses least read permissions,
keeps safe concurrency cancellation, and performs no deployment, runtime
activation, browser walkthrough, or secret import.

## Required Jobs

- `Product Cart MySQL Projection`
  - Runs `ListingDraftApiTests` methods for internal commerce context store
    provenance, public-field privacy, Auth enrichment visibility failure, and
    retryable Auth label outage.
  - Parses Surefire XML and fails if the required Product methods did not
    execute.
- `Order Cart Redis And Validation`
  - Requires concrete executable Redis cart release methods in
    `RedisCartRepositoryTests`: `mutationsAreAtomicVersionedAndExpiring`,
    `concurrentMutationsAreAtomicVersionedAndIsolated`, and
    `sameListingRetryIsDeterministicAndVersioned`.
  - This is intentionally fail-fast when the concurrency/retry methods are
    absent; textual source terms are not accepted as Redis
    concurrency/idempotency evidence.
  - Runs `RedisCartRepositoryTests`, `CartServiceTests`,
    `CartValidationServiceTests`, `CartControllerTests`,
    `CartAssessmentServiceWiringTests`, and the multi-business checkout
    calculation method.
  - Parses Surefire XML and fails if the required Order classes or methods did
    not execute.
- `Gateway Cart Boundary`
  - Runs focused cart gateway tests and then the full gateway package.
  - Proves cart enabled/disabled routing, checkout disabled independence,
    authentication, CSRF, bearer/correlation relay, spoofed identity header
    stripping, and zero-upstream disabled/guest behavior.
- `Frontend Cart Builds And Tests`
  - Verifies the expected focused cart spec text exists.
  - Runs production build, `demo-cart` build, and the full Angular test suite.
- `Cart Static Safety`
  - Runs whitespace and architecture checks.
  - Checks default-off frontend and gateway cart/checkout flags.
  - Scans cart DTO/UI response boundaries for allowed store fields and
    forbidden private/internal fields.
  - Confirms cart release candidates do not add or alter cart Flyway
    migrations.
  - Runs the high-confidence secret scan.

## Evidence Required For Publication

The later publication task must report:

- Candidate branch and exact head SHA.
- The `Cart Release Quality` run URL and final conclusion for that exact SHA.
- Per-job success with visible required test names.
- Confirmation that Product MySQL and Order Redis Testcontainers suites
  executed, not merely compiled.
- Production and `demo-cart` frontend build success.
- Static/default-off/privacy/migration/secret scans passing.

## Non-Goals

This gate does not:

- Create or publish a candidate branch.
- Stage, commit, push, deploy, or run a browser.
- Start local Docker, VM services, or shared runtime.
- Add cart features, migrations, checkout, payment, order creation, shipping,
  tracking, AI, notification, ORD-03, or SHP behavior.
