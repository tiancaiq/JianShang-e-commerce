# AI-DISC-SEARCH-P0-10A/10B Admin Search Maintenance Control Surface

Status: `SOURCE_GREEN_LOCAL_ONLY`, default-off. Runtime and browser acceptance
remain separate gates.

## Boundary

The Angular admin portal exposes Product-owned derived-search maintenance
operations only:

1. rebuild the legacy V1 keyword listing index;
2. create missing 04A embedding requests for existing public individual
   listings, one bounded server-owned page at a time;
3. operate the P0-08 V2 prepare/catch-up/promote/recover workflow through
   server-owned run state and command eligibility.

None of these operations edits authoritative listing rows. The page does not expose
provider, Agent, OpenSearch DSL, direct index, alias, cursor, watermark, topic,
or listing controls.

The production, development, AI demo, discovery demo, and cart demo builds set
`adminSearchMaintenance=false`. The explicit
`demo-admin-search-maintenance` build is the only source configuration that
sets it to true. The default Docker frontend build remains `production`.
When disabled, the admin navigation omits the entry, the route redirects to the
admin dashboard, and the service refuses every operation before `HttpClient`.

## Existing Product contracts

The page uses the existing same-origin gateway/BFF boundary:

```text
POST /api/v1/admin/search/listings/rebuild
POST /api/v1/admin/search/listings/embedding-request-backfills
GET  /api/v1/admin/search/listings/embedding-request-backfills/{runId}
POST /api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume
POST /api/v1/admin/search/listings/vector-rebuilds
GET  /api/v1/admin/search/listings/vector-rebuilds/{runId}
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/promote
POST /api/v1/admin/search/listings/vector-rebuilds/{runId}/recover
```

The existing admin route guard establishes a BFF session and verifies
`PLATFORM_ADMIN`. Product remains authoritative for authorization. Angular's
existing interceptor supplies cookies and the session CSRF header to POST
requests. The service never sets bearer tokens, actor headers, correlation
headers, or cookies itself.

Start and resume send no request body. A run ID is accepted only from the
strict Product response, held in component memory, and used for explicit
status refresh or a confirmed one-page resume. There is no automatic polling,
POST retry, cursor, watermark, topic, provider, index, alias, listing ID, or
other client control.

`AI-DISC-SEARCH-STAB-P1-11` makes the bodyless transport rule explicit for all
three maintenance commands, including the legacy rebuild. Angular uses
`HttpClient.request('POST', url)` with exactly the method and URL arguments;
the service does not pass a third options/body argument. It therefore does not
send JSON `null`, `{}`, an empty string, a service-created `Content-Type`,
`Content-Length`, or `Transfer-Encoding`. The existing interceptor may add only
the authenticated BFF session's CSRF header and credential mode.

Angular's `HttpTestingController` normalizes an omitted body to
`HttpRequest.body === null`, the same observable test value as an explicit
null body. The regression suite therefore verifies both the resulting absence
of `Content-Type` and, with a direct `HttpClient` spy, that every POST is
constructed with exactly two arguments. An authenticated browser/runtime wire
retest remains mandatory before activation.

`AI-DISC-SEARCH-STAB-P1-12` clarifies the matching Product transport rule:
HTTP framing does not constitute a command payload. A zero-byte fixed-length
request or empty chunked transfer is valid even when the gateway emits
`Transfer-Encoding: chunked`; any decoded body byte remains a strict
`LISTING_INVALID_REQUEST` before command invocation. The browser/runtime retest
must therefore verify the decoded command payload is empty rather than require
the absence of legal framing headers.

The legacy result parser accepts exactly `engine`, `index`, and
`indexedCount`, but the UI displays only the stable indexed count. The P0-09
parser accepts the exact
`MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1` response and
displays only its run reference, fixed state, bounded counts, and timestamps.
Unexpected fields or types fail closed as a safe unavailable response.

`AI-DISC-SEARCH-P0-10B` adds the V2 operator control surface after Product
`AI-DISC-SEARCH-STAB-P1-13` made command eligibility authoritative in
`MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2`. The Angular parser accepts only
that strict schema with the allowlisted durable state, command outcome, safe
roles, bounded counts, timestamps, and Product-derived `canCatchUp`,
`canPromote`, and `canRecover` booleans. The UI never derives promotion
readiness from `catchUpWorkCount`, local state names, or timestamp comparisons.

V2 prepare, catch-up, promote, and recover are each separate bodyless POST
commands with operation-specific review and final confirmation. The component
stores only the server-returned run ID in memory, refreshes status only when the
administrator asks, and enables catch-up, promote, or recover only from the
corresponding Product boolean. Backend authorization, fence checks, alias
validation, rollback, and idempotency remain authoritative.

The V2 status display is intentionally bounded: run reference, fixed durable
state, schema role labels, bounded document/work/receipt counts, timestamps,
and command eligibility. It does not show physical index or alias names,
OpenSearch requests/responses, hashes, listing IDs/content, vectors, prompts,
credentials, raw backend errors, or operator identity.

## Interaction and safety

Every POST has a separate inline confirmation naming the exact operation.
Pending commands disable all operation buttons, and failures never trigger an
automatic replay. Status refresh is GET-only and user initiated. One resume
confirmation advances at most one server-owned page. V2 catch-up/promote/recover
conflicts require a manual status refresh; the client does not retry or continue
hidden sequencing.

The page never renders or records raw errors, cookies, tokens, authenticated
admin identity, listing content, internal index details, prompts, vectors,
provider data, or Product response bodies. Accessible operation headings,
native buttons, labelled confirmation groups, keyboard focus styles, a single
polite live region, reduced-motion behavior, and a responsive two-column count
summary are part of the contract.

## Deferred

- Runtime flag activation and authenticated browser acceptance.
- Provider, Agent worker drain, V2 rebuild execution, alias promotion, and
  hybrid runtime activation.
- Automatic polling, scheduled maintenance, or startup commands.
- Any Product, Agent, gateway, provider, OpenSearch, or database behavior
  change.
