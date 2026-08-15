package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;

@Component
public class RestProductCommerceClient implements ProductCommerceClient {

    private static final Logger log = LoggerFactory.getLogger(RestProductCommerceClient.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestProductCommerceClient(
            RestClient.Builder builder,
            @Value("${service.product.url}") String productServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(productServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    // Loads current catalog facts without forwarding the buyer credential.
    @Override
    public Optional<ProductContext> find(String listingId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/api/v1/internal/store/items/{listingId}/commerce-context", listingId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .body(ProductContext.class));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new CartException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CART_DEPENDENCY_UNAVAILABLE",
                    "Product validation is temporarily unavailable.");
        }
    }

    // Fails closed when Product cannot authoritatively decide listing purchasability.
    @Override
    public void requirePurchasable(Set<String> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            throw unavailable();
        }
        try {
            CapabilityBatchResponse response = client.post()
                    .uri("/api/v1/internal/listings/capabilities/evaluate-batch")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(new CapabilityBatchRequest(listingIds, Set.of("LISTING_PURCHASABILITY")))
                    .retrieve()
                    .body(CapabilityBatchResponse.class);
            if (response == null || response.listings() == null
                    || response.listings().size() != listingIds.size()
                    || response.listings().stream().anyMatch(item -> item == null
                    || item.listingId() == null || !listingIds.contains(item.listingId())
                    || item.decisions() == null || item.decisions().stream().noneMatch(decision ->
                    "LISTING_PURCHASABILITY".equals(decision.scope()) && decision.allowed()))) {
                boolean restricted = response != null && response.listings() != null
                        && response.listings().stream().filter(java.util.Objects::nonNull)
                        .flatMap(item -> item.decisions() == null ? java.util.stream.Stream.empty()
                                : item.decisions().stream())
                        .anyMatch(decision -> "LISTING_PURCHASABILITY".equals(decision.scope())
                                && !decision.allowed());
                if (restricted) {
                    throw new CheckoutException(HttpStatus.FORBIDDEN,
                            "LISTING_PURCHASABILITY_RESTRICTED",
                            "This item is currently unavailable for purchase.");
                }
                throw unavailable();
            }
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            Integer status = exception instanceof RestClientResponseException responseError
                    ? responseError.getStatusCode().value()
                    : null;
            Throwable cause = exception.getCause();
            log.warn("Listing capability decision failed outcome=dependency_error status={} type={} causeType={}",
                    status, exception.getClass().getSimpleName(),
                    cause == null ? "none" : cause.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private CheckoutException unavailable() {
        return new CheckoutException(HttpStatus.SERVICE_UNAVAILABLE,
                "LISTING_ENFORCEMENT_DECISION_UNAVAILABLE",
                "Listing availability is temporarily unavailable.");
    }

    private record CapabilityBatchRequest(Set<String> listingIds, Set<String> scopes) { }
    private record CapabilityBatchResponse(Instant evaluatedAt, List<ListingCapabilityDecision> listings) { }
    private record ListingCapabilityDecision(String listingId, List<CapabilityDecision> decisions) { }
    private record CapabilityDecision(String scope, boolean allowed, String effectiveAction,
            String enforcementActionId, Instant expiresAt, String supportReference) { }
}
