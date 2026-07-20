# V2-NOT-01C Default-Off Notification Center UI And Gateway Boundary

Status: source-complete pending source verification; NOT-01A/B disposable
MySQL verification is still required before the business lane can advance.

## Boundary

This slice adds only a default-off gateway route and Angular marketplace
account notification center for the existing NOT-01B read API:

- `GET /api/v1/notifications?cursor=&limit=`;
- `POST /api/v1/notifications/{notificationId}/read`;
- `POST /api/v1/notifications/read-all`.

It does not add Notification Service behavior, migrations, Kafka, email,
preferences, polling, a count endpoint, WebSocket, business/admin
notifications, shipping, cancellation, refund, inventory, payment, runtime
activation, browser verification, or deployment.

## Gateway

`msb.gateway.features.notifications=false` owns
`/api/v1/notifications/**` locally and returns an authenticated hidden 404
without calling Notification Service. When explicitly enabled, the gateway
requires normal authentication, relays the bearer token and correlation ID,
strips browser-supplied identity headers, enforces CSRF for POST commands, and
wraps Notification Service calls in a circuit breaker with the standard safe
fallback body.

The gateway feature is independent from cart, checkout, address, order, AI,
and business-order flags.

## Angular

`environment.features.notifications=false` hides the `/account/notifications`
route and the account-dashboard navigation tile. Disabled route construction
redirects to `/account` and performs no notification client calls.

When enabled, the notification center:

- uses only the authenticated BFF session; it never sends actor/user IDs;
- lists notifications with the opaque server cursor and bounded limit;
- renders only `ORDER_CONFIRMED_V1` with the local allowlisted `/account`
  route;
- validates DTOs and fails closed for unsafe routes, unknown types, corrupt
  args, or internal fields;
- marks one or all notifications read with empty POST bodies and no automatic
  retry after an uncertain command failure;
- preserves server read timestamps by not fabricating `readAt` locally;
- provides loading, empty, outage, reauth, retry, focus, ARIA, and responsive
  states.

No badge count, polling, WebSocket, raw HTML, raw argument rendering, provider
metadata, source-event metadata, or cross-user data is exposed.

## Verification

Required coverage:

- gateway default-off local 404, enabled token relay, spoofed-header stripping,
  guest rejection, POST CSRF, and circuit-breaker fallback;
- Angular route default-off redirect, enabled auth guard, account-dashboard
  navigation silence, strict client DTO parsing, unsafe-route/corrupt-arg
  rejection, no-body read commands, disabled component zero-work, loading,
  empty, outage, reauth, retry, read-one/read-all, and responsive/accessibility
  hooks;
- frontend build and focused/full gateway package checks;
- credential, unsafe-route, internal-field, default-off, and whitespace scans.

Business remains `0/3` until the NOT-01A and NOT-01B disposable MySQL suites
execute green on an available runner.
