# P1-07 Common Web Reference

## Purpose

`common-web` provides technical HTTP conventions shared by the gateway and
backend services:

- safe `X-Correlation-Id` handling
- correlation context for logs
- the standard API error response
- common exception-to-HTTP mappings

It does not contain marketplace domain behavior.

## Add the Module

Services in this repository already include the module. A new Spring Boot
servlet service should add:

```xml
<dependency>
    <groupId>com.msb.ecom</groupId>
    <artifactId>common-web</artifactId>
    <version>${project.version}</version>
</dependency>
```

Spring Boot discovers `CommonWebAutoConfiguration` automatically. Do not
manually register the filter or exception handler unless a service has an
approved reason to replace them.

## Automatic Request Flow

For every servlet request:

1. `CorrelationIdFilter` reads `X-Correlation-Id`.
2. A valid client value is preserved.
3. A missing, invalid, or oversized value is replaced with a generated UUID.
4. The final value is available in the downstream request header.
5. The final value is placed in the response header and SLF4J MDC.
6. `CommonApiExceptionHandler` includes it in handled error responses.
7. MDC is restored after the request to prevent request context leakage.

Safe client values:

- begin with an ASCII letter or number
- contain only letters, numbers, `.`, `_`, `:`, or `-`
- contain no more than 128 characters

Clients should treat the returned correlation ID as an opaque diagnostic
value.

## CorrelationId API

Class:
`com.msb.ecom.common.web.correlation.CorrelationId`

### `CorrelationId.parse(String value)`

Validates a correlation ID and returns a typed value.

Use this when application code must reject an invalid internally supplied ID.
It throws `IllegalArgumentException` when the value is unsafe.

```java
CorrelationId id = CorrelationId.parse("request-123");
String value = id.value();
```

Do not use it directly on an inbound HTTP header. The filter already handles
untrusted headers without failing the request.

### `CorrelationId.generate()`

Creates a valid random UUID correlation ID.

```java
CorrelationId id = CorrelationId.generate();
```

Use it for work that starts outside an HTTP request, such as a scheduled job.
When consuming an event, preserve the event correlation ID instead of
generating a new one when a valid ID is available.

### `CorrelationId.acceptOrGenerate(String candidate)`

Returns the supplied value when it is valid. Otherwise, it generates a new
correlation ID.

```java
CorrelationId id = CorrelationId.acceptOrGenerate(candidate);
```

This is primarily used by request-entry adapters such as
`CorrelationIdFilter`. Normal controllers do not need to call it.

### `CorrelationId.value()`

Returns the validated string value.

```java
String correlationId = id.value();
```

### `CorrelationId.toString()`

Returns the same validated string as `value()`. Prefer `value()` when passing
the ID into API or event fields because it is more explicit.

### Constants

| Constant | Value or purpose |
| --- | --- |
| `CorrelationId.HEADER_NAME` | `X-Correlation-Id` |
| `CorrelationId.MAX_LENGTH` | `128` |

## CorrelationIdFilter API

Class:
`com.msb.ecom.common.web.correlation.CorrelationIdFilter`

### `CorrelationIdFilter.current(HttpServletRequest request)`

Returns the correlation ID assigned to the current request.

```java
@GetMapping("/example")
ResponseEntity<Void> example(HttpServletRequest request) {
    String correlationId = CorrelationIdFilter.current(request);
    return ResponseEntity.noContent()
            .header(CorrelationId.HEADER_NAME, correlationId)
            .build();
}
```

Normally the response header is already added by the filter. Call `current`
when the value must be included in an event, outbound request, audit record,
or manually constructed error.

If the filter did not process the request, `current` generates a new ID. This
fallback should mainly matter in isolated tests or nonstandard dispatching.

### `CorrelationIdFilter.MDC_KEY`

The MDC key is `correlationId`.

```java
String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
```

Normal service logs written during an HTTP request automatically have this
MDC value available. Logging configuration must include MDC fields for the
value to appear in rendered console logs. Structured logging encoders should
emit the MDC field.

### `CorrelationIdFilter.REQUEST_ATTRIBUTE`

The request attribute key used internally to store the final ID. Prefer
`CorrelationIdFilter.current(request)` instead of reading this attribute
directly.

### Outbound Service Calls

The filter makes the final value available as a request header, but an HTTP
client still needs to forward it:

```java
String correlationId = CorrelationIdFilter.current(request);

webClient.get()
        .uri(targetUrl)
        .header(CorrelationId.HEADER_NAME, correlationId)
        .retrieve();
```

The same rule applies to `RestClient`, Kafka event envelopes, and other
outbound adapters.

## Error Response Types

### `FieldError`

Represents one invalid field:

```java
FieldError error = new FieldError("quantity", "OUT_OF_RANGE");
```

Both `field` and `code` must be non-null and nonblank.

### `ApiError`

Represents the error body:

```java
ApiError error = new ApiError(
        "LISTING_NOT_FOUND",
        "The listing was not found.",
        List.of(),
        correlationId
);
```

Fields:

| Field | Meaning |
| --- | --- |
| `code` | Stable machine-readable error code |
| `message` | Safe user-facing message |
| `fieldErrors` | Field-level errors, or an empty list |
| `correlationId` | ID used to locate related logs |

The constructor rejects blank `code`, `message`, and `correlationId`. A null
`fieldErrors` value becomes an immutable empty list.

Do not place exception messages, stack traces, secrets, access tokens, or
unnecessary PII in `message`.

### `ApiErrorEnvelope`

Wraps `ApiError` under the required top-level `error` property:

```java
ApiErrorEnvelope envelope = new ApiErrorEnvelope(error);
```

Serialized response:

```json
{
  "error": {
    "code": "LISTING_NOT_FOUND",
    "message": "The listing was not found.",
    "fieldErrors": [],
    "correlationId": "request-123"
  }
}
```

The constructor rejects a null error.

## Automatic Exception Mapping

`CommonApiExceptionHandler` is a Spring `@RestControllerAdvice`. Controllers
should throw appropriate exceptions and let Spring call these methods.
Application code should not instantiate or invoke the handler directly.

| Exception | Status | Error code | Public message |
| --- | --- | --- | --- |
| `MethodArgumentNotValidException` | `400` | `VALIDATION_FAILED` | `One or more fields are invalid.` |
| `ConstraintViolationException` | `400` | `VALIDATION_FAILED` | `One or more fields are invalid.` |
| `HttpMessageNotReadableException` | `400` | `MALFORMED_REQUEST` | `The request body is malformed.` |
| `IllegalArgumentException` | `400` | `INVALID_REQUEST` | `The request is invalid.` |
| `IllegalStateException` | `401` | `UNAUTHENTICATED` | `Authentication is required.` |
| Any other `Exception` | `500` | `INTERNAL_ERROR` | `An unexpected error occurred.` |

The catch-all handler logs the original exception with its correlation ID but
returns only a safe generic message.

Feature-specific errors such as `LISTING_NOT_FOUND`, `FORBIDDEN`, or
`VERSION_CONFLICT` should use a feature exception mapped by that service to an
`ApiErrorEnvelope`. Do not use `IllegalArgumentException` merely to obtain a
particular HTTP status.

## Validation Example

```java
public record CreateExampleRequest(
        @NotBlank String name,
        @Positive int quantity
) {
}

@PostMapping("/api/v1/examples")
ResponseEntity<Void> create(@Valid @RequestBody CreateExampleRequest request) {
    return ResponseEntity.noContent().build();
}
```

Invalid input is automatically returned as `VALIDATION_FAILED`, with
field-level codes derived from the validation annotation names.

## Manually Returning a Feature Error

Use this only when an exception mapper is not appropriate:

```java
String correlationId = CorrelationIdFilter.current(request);
ApiError error = new ApiError(
        "VERSION_CONFLICT",
        "The resource changed. Refresh and try again.",
        List.of(),
        correlationId
);

return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiErrorEnvelope(error));
```

Prefer a service-specific exception and centralized mapper when the same
error can occur in multiple endpoints.

## Known Boundary

Spring Security may produce `401` or `403` before an MVC controller is
reached. Standardizing those responses belongs to the Keycloak/security
integration. Until then, do not assume every security-generated response uses
`ApiErrorEnvelope`.

