# API Gateway Instructions

These rules apply under `api-gateway/` in addition to the repository
instructions.

## Gateway boundary

- Keep the gateway focused on routing, edge authentication integration,
  correlation propagation, and approved transport concerns. Business rules and
  resource authorization belong to the owning backend service.
- External routes use `/api/v1` and follow `docs/mvp/api-contract.md`.
- Preserve the standard error envelope and correlation ID without leaking
  downstream internals, credentials, tokens, or unnecessary PII.
- Forward only approved headers and identity context. Never trust client-supplied
  roles, user IDs, business IDs, prices, totals, or payment state.
- Route additions must target an existing approved service boundary; do not use
  the gateway as a runtime common service or direct data-access layer.
- Preserve backward compatibility or version the public contract.

## Authorization and resilience

- Gateway checks may reject obviously unauthenticated or unauthorized traffic,
  but they never replace downstream actor, tenant, ownership, or permission
  checks.
- Keep timeouts, body limits, retries, and failure mapping bounded and suitable
  for the endpoint semantics. Never retry non-idempotent commands implicitly.
- Require and propagate `Idempotency-Key` for approved retryable money,
  inventory, checkout, order, shipping, and webhook commands.
- Treat all request input and downstream error content as untrusted.

## Verification

Test route mapping, identity and correlation propagation, public/protected
boundaries, cross-surface denial, header filtering, timeout/failure mapping,
and backward-compatible error behavior. Include integration tests for every
new or changed route.
