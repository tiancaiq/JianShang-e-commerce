# Frontend Instructions

These rules apply under `frontend/` in addition to the repository instructions.

## Angular scope

- Angular is the supported frontend framework.
- Implement the smallest user-visible path required by the approved slice and
  keep generated API/client types aligned with the contract.
- Treat client roles, user IDs, business IDs, prices, totals, and status as
  untrusted. UI guards improve navigation but never replace backend
  authorization.
- Feature-flag incomplete or unapproved workflows and preserve a usable path
  when optional AI features are unavailable.

## Product surfaces

- Marketplace pages use commerce patterns: prominent search, category
  navigation, image-forward listing cards/details, seller-type labels, and
  buyer-facing actions.
- Business seller pages use a management-dashboard style for onboarding, store
  profile, and listing management.
- Admin pages use a management-dashboard style for queues, filters, decision
  forms, audit context, and moderation safety.
- Do not make seller or admin surfaces mimic the marketplace. Shared primitives
  are welcome, but layouts should fit each user's job.
- Keep individual seller profile and listing management in the marketplace
  account experience, not the business portal.
- Follow `docs/mvp/ui/marketplace-ui-redesign.md` for public marketplace UI
  work.

## Shared UI and safety

- Put proven, small primitives such as status pills, empty states, simple
  cards, and table shells in `src/app/shared/components/ui/`.
- Keep primitives theme-token driven so marketplace, seller, and admin remain
  distinct. Do not build a broad design system or move surface-specific layout
  into shared code before useful duplication exists.
- Do not expose exact individual meeting or home locations. Render only the
  masked or public-safe fields allowed by the API contract.
- Treat chat, media metadata, and server-provided rich text as untrusted.

## Verification

Add or update component and service tests for user-visible behavior, routing,
capability gates, error states, and contract parsing. Use end-to-end tests when
the slice crosses routes or services.
