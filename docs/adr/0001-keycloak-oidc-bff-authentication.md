# ADR-0001: Keycloak OIDC with Gateway BFF

- Status: Accepted
- Date: 2026-06-15
- Roadmap: P1-04
- Related requirements: IAM-01, IAM-02, IAM-03, IAM-06, ADM-01

## Context

The repository currently contains conflicting authentication approaches:

- `auth-service` issues custom HS256 JWTs after checking passwords.
- The Angular client stores the JWT and user object in `localStorage`.
- `auth-service` also contains an HTTP-session authentication filter, while
  Spring Security is configured as stateless and the filter is not installed.
- The API gateway uses Spring OAuth2 Resource Server APIs, but validates the
  same shared HS256 secret rather than a configured OIDC issuer.
- Older documentation describes Keycloak, but the running code does not
  implement a complete Keycloak login, refresh, or logout flow.

The current custom path does not provide refresh-token rotation, session
revocation, email verification, password recovery, MFA, signing-key rotation,
or reliable role claims. Its JWT subject can be a nullable username, every
validator requires the signing secret, and a browser XSS vulnerability could
read tokens from `localStorage`.

The MVP needs one identity across buyer, individual seller, business staff,
and admin access. It must also support independent service authorization,
business tenant checks, admin hardening, and future scale without making the
marketplace team responsible for implementing an identity provider.

## Decision

Use **Keycloak as the sole identity provider**, using OpenID Connect (OIDC).
Use the **API gateway as a backend-for-frontend (BFF)** for all first-party web
applications.

The Angular user site, business seller site, and admin site will not collect
passwords for the application backend and will not store access or refresh
tokens in browser storage.

Keycloak owns:

- Credentials and password hashing
- Login and account sessions
- Email verification
- Password recovery
- MFA and required authentication actions
- Access, ID, and refresh token issuance
- Signing keys and key rotation

The application identity/profile boundary owns:

- The internal user record keyed to immutable Keycloak `sub`
- Display name, phone, avatar, status, and marketplace preferences
- Platform role assignments and audit history
- Individual seller activation
- Business memberships and permissions

Keycloak is not authoritative for business membership, listing ownership,
orders, trades, or other marketplace resources.

## Browser Flow

1. The browser requests a protected route or the BFF login endpoint.
2. The gateway starts OIDC Authorization Code flow with PKCE and redirects to
   Keycloak.
3. Keycloak authenticates the user and redirects to the gateway callback.
4. The gateway completes the code exchange and creates an opaque browser
   session.
5. The browser receives only a `Secure`, `HttpOnly`, `SameSite=Lax` session
   cookie. It does not receive OAuth tokens.
6. The gateway obtains or refreshes an access token and relays it to the
   destination API.

Browser sessions must use a shared, expiring session store suitable for
multiple gateway instances. Redis is the planned store. Session records and
refresh tokens must be encrypted or otherwise protected at rest.

Because authentication uses a cookie, state-changing browser requests require
CSRF protection. CORS must use explicit first-party origins and must not
combine credentials with wildcard origins.

## Token Contract

Access tokens are short-lived asymmetric JWTs signed by Keycloak. Initial
defaults:

- Access token lifetime: 5 minutes
- Browser session idle timeout: 30 minutes
- Browser session maximum lifetime: 12 hours
- Refresh-token rotation and reuse detection: enabled

Exact timeout values remain deployment configuration, but production may not
use non-expiring access or refresh tokens.

Required access-token validation:

- Signature from the issuer's JWKS
- Exact issuer
- Intended API audience
- Expiration and not-before time
- Allowed signing algorithm

The stable actor identifier is the Keycloak `sub` claim. Email, display name,
and username are not identifiers. APIs map `sub` to the application's internal
user ID.

Tokens may carry coarse platform roles such as `BUYER`,
`INDIVIDUAL_SELLER`, `BUSINESS_USER`, and `ADMIN_ELIGIBLE`. Services must not
trust token roles as proof of resource ownership or current business access.
Every business operation checks current `businessId` membership and permission
in the owning application service.

## Refresh and Logout

The gateway performs refresh using the server-held refresh token. It rotates
the stored token after every successful refresh. A detected replay or failed
rotation terminates the local session and requires authentication again.

Logout is idempotent and performs both:

1. Delete the gateway session and its stored tokens.
2. Invoke Keycloak OIDC logout/revocation so the identity-provider session is
   ended when supported.

Password reset, account suspension, or security-sensitive role removal must
revoke active Keycloak sessions. Short access-token lifetime limits the period
before independently validating services observe revocation.

## Service Validation

The gateway performs coarse route authentication, but it is not the sole
security boundary.

Every protected Spring service will be an OAuth2 Resource Server and validate
the Keycloak JWT independently using issuer metadata/JWKS. Services then apply
their own role, ownership, account-status, and tenant checks.

Services must not:

- Share a symmetric JWT signing secret
- Accept identity headers from an untrusted caller
- Query Keycloak for every normal API request
- Treat gateway validation as sufficient authorization

Service-to-service calls use separate confidential clients with client
credentials and narrow audiences/scopes. A service identity cannot be
silently treated as an end-user identity. When user delegation is required,
the original user context must be propagated explicitly and audited.

## Admin Authentication

Admin access uses a separate Keycloak client and stricter policy:

- MFA is mandatory
- Shorter idle and maximum session timeouts
- No self-service grant of admin roles
- Granular application permissions checked by the target service
- Every administrative mutation produces an immutable audit record
- Sensitive operations may require recent authentication or step-up MFA
- Production admin access should support conditional access or an IP/VPN
  restriction when operationally available

An `ADMIN_ELIGIBLE` token claim only permits entry to the admin surface. It
does not replace application permission or resource checks.

## Comparison

| Area | Existing custom JWT/session | Keycloak OIDC with gateway BFF |
|---|---|---|
| Current completeness | Login JWT only; session path conflicts with stateless configuration | Standard login, session, refresh, logout, recovery, verification, and MFA capabilities |
| Browser token safety | JWT stored in `localStorage` | OAuth tokens remain server-side; browser has an opaque HttpOnly cookie |
| Signing | Shared HS256 secret distributed to validators | Asymmetric signing and JWKS-based key rotation |
| Revocation | No refresh session or implemented logout revocation | Gateway session deletion plus Keycloak session/token revocation |
| Admin MFA | Must be designed and implemented | Supported through Keycloak authentication flows |
| Service validation | Gateway validates a shared secret; services are inconsistent | Every service validates issuer, audience, signature, and time claims |
| Engineering burden | Application team owns identity security and protocol behavior | Application team integrates maintained OIDC standards |
| Operational burden | Lower initial infrastructure, high custom security maintenance | Additional Keycloak and session-store operations |
| Vendor portability | Custom token and endpoint contract | OIDC-compatible boundary allows a future managed provider |
| MVP suitability | Requires substantial security implementation before feature work | More setup, but materially lower identity implementation risk |

## Rejected Alternatives

### Continue the custom JWT implementation

Rejected because correcting it requires building and operating password
recovery, email verification, refresh rotation, revocation, MFA, signing-key
rotation, standards-compliant OIDC behavior, and administrative controls.
Those are security-critical capabilities but not marketplace differentiators.

### Direct SPA tokens with Authorization Code and PKCE

Rejected for the first-party web applications because the BFF pattern avoids
exposing access and refresh tokens to browser JavaScript and fits the existing
gateway. Public clients using PKCE may be reconsidered for a future native
mobile application.

### Stateful custom application sessions without OIDC

Rejected because it still leaves credential lifecycle, federation, MFA, and
identity security inside application code and does not provide standard JWT
validation for downstream services.

## Consequences

Positive consequences:

- One authentication authority and protocol
- No new custom password or token implementation
- Stronger browser token isolation and admin MFA support
- Independent JWT verification by services
- Standards-based path to a managed OIDC provider later

Costs and constraints:

- Keycloak and the shared gateway session store become critical infrastructure
- Cookie authentication requires CSRF controls
- Keycloak realm/client configuration must be versioned and promoted between
  environments
- The application must synchronize or provision an internal user record for
  each Keycloak subject
- Session, token refresh, key rotation, and identity-provider outages require
  explicit monitoring and recovery procedures

## Migration Boundary

This ADR does not implement registration or login.

Later authentication slices must:

1. Remove custom JWT issuance, the shared HS256 secret, and the unused custom
   session path.
2. Replace Angular `localStorage` authentication with BFF session awareness.
3. Configure Keycloak realms, clients, roles, MFA policy, and version-controlled
   realm configuration.
4. Configure gateway OIDC login, server-side session storage, token relay,
   refresh, logout, CSRF, and explicit CORS origins.
5. Configure every protected backend as a Keycloak OAuth2 Resource Server.
6. Reconcile the `/api/v1/auth` contract and identity database model with this
   decision before implementing IAM-01 through IAM-03.

Until that migration is complete, existing authentication code is legacy and
must not be extended for new MVP features.
