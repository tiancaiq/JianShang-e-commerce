package com.msb.ecom.auth_service.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
final class RestGovernanceDomainExecutor implements GovernanceDomainExecutor {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient payment;
    private final RestClient product;
    private final String internalToken;
    private final ObjectMapper mapper;

    RestGovernanceDomainExecutor(RestClient.Builder builder,
                                 @Value("${service.payment.url}") String paymentUrl,
                                 @Value("${service.product.url}") String productUrl,
                                 @Value("${commerce.internal-service-token}") String internalToken,
                                 ObjectMapper mapper) {
        this.payment = builder.clone().baseUrl(paymentUrl).build();
        this.product = builder.clone().baseUrl(productUrl).build();
        this.internalToken = internalToken;
        this.mapper = mapper;
    }

    @Override
    public Validation validate(String actionType, String targetId, Map<String, Object> payload,
                               String executorAccessToken) {
        try {
            return switch (actionType) {
                case "LARGE_REFUND" -> validateRefund(targetId, payload, executorAccessToken);
                case "HIGH_IMPACT_CATALOG_CATEGORY_DISABLE" -> validateCatalog(targetId, payload, executorAccessToken);
                default -> Validation.invalid("GOVERNANCE_ACTION_UNSUPPORTED",
                        "The governed domain action is not supported.");
            };
        } catch (RuntimeException exception) {
            return Validation.invalid("GOVERNANCE_DOMAIN_UNAVAILABLE",
                    "The owning domain could not revalidate the approved action.");
        }
    }

    @Override
    public Execution execute(String actionType, String targetId, Map<String, Object> payload,
                             String executorAccessToken, String idempotencyKey, String correlationId) {
        try {
            return switch (actionType) {
                case "LARGE_REFUND" -> executeRefund(targetId, payload, executorAccessToken,
                        idempotencyKey, correlationId);
                case "HIGH_IMPACT_CATALOG_CATEGORY_DISABLE" -> executeCatalog(targetId, payload,
                        executorAccessToken, idempotencyKey, correlationId);
                default -> Execution.failed("GOVERNANCE_ACTION_UNSUPPORTED",
                        "The governed domain action is not supported.");
            };
        } catch (RuntimeException exception) {
            return Execution.failed("GOVERNED_DOMAIN_EXECUTION_FAILED",
                    "The owning domain did not accept the approved action.");
        }
    }

    private Validation validateRefund(String paymentId, Map<String, Object> payload, String token) {
        RefundPreview preview = payment.post()
                .uri("/api/v1/admin/payments/{id}/refund/dry-run", paymentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .body(refundBody(payload, null))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException("Refund revalidation was rejected.");
                })
                .body(RefundPreview.class);
        if (preview == null || !preview.allowed()
                || preview.expectedPaymentVersion() != number(payload, "expectedPaymentVersion")
                || preview.requestedAmount() == null
                || preview.requestedAmount().compareTo(decimal(payload, "amount")) != 0) {
            return Validation.invalid("GOVERNANCE_REFUND_STALE",
                    "The payment refund amount or eligibility changed after approval.");
        }
        return Validation.valid(json(payload));
    }

    private Validation validateCatalog(String categoryId, Map<String, Object> payload, String token) {
        CatalogPreview preview = product.post()
                .uri("/api/v1/admin/catalog/categories/{id}/status/dry-run", categoryId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .body(catalogBody(payload))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException("Catalog revalidation was rejected.");
                })
                .body(CatalogPreview.class);
        if (preview == null || preview.categoryVersion() != number(payload, "expectedCategoryVersion")
                || preview.counts() == null
                || preview.counts().activeListings() != number(payload, "activeListingCount")
                || preview.counts().draftListings() != number(payload, "draftListingCount")
                || preview.counts().pendingListings() != number(payload, "pendingListingCount")) {
            return Validation.invalid("GOVERNANCE_CATALOG_STALE",
                    "The category version or listing impact changed after approval.");
        }
        return Validation.valid(json(payload));
    }

    private Execution executeRefund(String paymentId, Map<String, Object> payload, String token,
                                    String key, String correlationId) {
        RefundExecution response = payment.post()
                .uri("/api/v1/internal/admin/payments/{id}/refund", paymentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", correlationId)
                .body(refundBody(payload, key))
                .retrieve().body(RefundExecution.class);
        return response == null || response.refundId() == null
                ? Execution.failed("GOVERNED_REFUND_FAILED", "Payment did not return a refund reference.")
                : Execution.succeeded(response.refundId());
    }

    private Execution executeCatalog(String categoryId, Map<String, Object> payload, String token,
                                     String key, String correlationId) {
        CatalogExecution response = product.post()
                .uri("/api/v1/internal/admin/catalog/categories/{id}/status", categoryId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", correlationId)
                .body(catalogBody(payload))
                .retrieve().body(CatalogExecution.class);
        return response == null || response.category() == null || response.category().id() == null
                ? Execution.failed("GOVERNED_CATALOG_FAILED", "Product did not return a category reference.")
                : Execution.succeeded(response.category().id());
    }

    private RefundBody refundBody(Map<String, Object> payload, String key) {
        String dispute = text(payload, "disputeId");
        return new RefundBody(text(payload, "refundType"), decimal(payload, "amount"),
                text(payload, "currency"), text(payload, "reasonCode"), text(payload, "reason"),
                dispute.isBlank() ? null : dispute, number(payload, "expectedPaymentVersion"), key);
    }

    private CatalogBody catalogBody(Map<String, Object> payload) {
        return new CatalogBody(number(payload, "expectedCategoryVersion"),
                text(payload, "proposedStatus"), text(payload, "reason"));
    }

    private String bearer(String token) {
        if (token == null || token.isBlank()) throw new IllegalStateException("Executor access token is unavailable.");
        return "Bearer " + token;
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private BigDecimal decimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Governance payload could not be serialized."); }
    }

    private record RefundBody(String refundType, BigDecimal amount, String currency,
                              String reasonCode, String reason, String disputeId,
                              long expectedPaymentVersion, String idempotencyKey) { }
    private record RefundPreview(BigDecimal requestedAmount, boolean allowed,
                                 long expectedPaymentVersion) { }
    private record RefundExecution(String refundId) { }
    private record CatalogBody(long expectedVersion, String status, String reason) { }
    private record CatalogCounts(long activeListings, long draftListings, long pendingListings) { }
    private record CatalogPreview(CatalogCounts counts, long categoryVersion) { }
    private record CategoryRef(String id) { }
    private record CatalogExecution(CategoryRef category) { }
}
