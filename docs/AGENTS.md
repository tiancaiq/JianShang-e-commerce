# Documentation Instructions

These rules apply under `docs/` in addition to the repository instructions.

## MVP organization

Keep the top-level MVP specifications in `docs/mvp/`:

- `requirements.md`
- `architecture.md`
- `database.md`
- `api-contract.md`
- `development-roadmap.md`

Place roadmap slice documents in their owning feature folder:

- `iam/`: accounts and authentication
  - `core/`: core IAM setup
  - `login/`: login and sessions
  - `signup/`: sign-up and providers
  - `user-profile/`: profile and avatar
- `bus/`: business onboarding and stores
- `ind/`: individual seller profiles
- `list/`: listings and media
- `search/`: search and storefront
- `chat/`: messaging
- `ai/`: agents and automated operations
- `site/`: route and product-surface separation
- `ui/`: product design direction
- `fix/`: verification and demo fixes
  - `stabilization/general/`
  - `stabilization/auth-login/`
  - `stabilization/pre-search/`
  - `stabilization/post-search/`

When adding a slice document, update its references in
`docs/mvp/development-roadmap.md`. Do not add a slice directly under
`docs/mvp/` unless it creates a new top-level MVP area and updates this folder
structure at the same time.

## Contract maintenance

- Treat approved MVP documents as a coordinated contract; keep requirement,
  architecture, database ownership, API, and roadmap references consistent.
- Record intentional release-placement or product-invariant changes explicitly;
  do not let a tutorial or README redefine them.
- Keep status and verification claims evidence-based. Distinguish implemented,
  locally verified, rollout-gated, and deferred work.
- Documentation-only work must not alter application or environment files.
