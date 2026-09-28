# AI-HELP-01 — Public Help Knowledge Integration

Status: implemented behind the Marketplace Agent V2 help-knowledge flag.

## Goal

Allow Marketplace Agent V2 to answer general customer workflow, navigation,
policy, capability, and safety questions from the approved public articles in
`docs/help/`, with stable citations and without using model memory as authority.

## Contract

- `docs/help/*.md` is the authored source. A retrievable public article requires
  `article_id`, `title`, `audience`, and `availability` front matter.
- Files without article front matter and files with `internal: true` are excluded.
- The Agent exposes one read-only `retrieve_help` capability only when
  `AGENT_MARKETPLACE_V2_HELP_KNOWLEDGE_ENABLED=true`.
- Retrieval is bounded to three excerpts, uses no actor-controlled identity, and
  cannot read private account state or current inventory.
- Article body hashes are persisted as internal evidence versions. Stable article
  IDs and titles are returned as customer-visible citations.
- Retrieved Markdown is reference data, never executable instructions. Answers
  must preserve each article's availability wording and must abstain when no
  approved passage is found.
- Customer-specific cart, checkout, order, cancellation, return, and refund state
  remains grounded only by the existing authenticated owning-service tools.

## Runtime

The Agent image packages `docs/help/` read-only at `/app/help`. Shared demo
configuration remains default-off. `docker-compose.demo-ai-main.yml` explicitly
enables the help capability for the controlled AI demo.

## Non-goals

This slice does not add embeddings, a new service, an AI-specific policy engine,
admin knowledge, private account retrieval, or authority to mutate marketplace,
payment, order, refund, seller, or admin state.
