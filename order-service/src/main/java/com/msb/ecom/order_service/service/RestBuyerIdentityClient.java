package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.order_service.model.CheckoutException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Component
public class RestBuyerIdentityClient implements BuyerIdentityClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestBuyerIdentityClient(
            RestClient.Builder builder,
            @Value("${service.auth.url}") String authServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(authServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public BuyerAddress resolveAddress(String subject, String addressId) {
        try {
            BuyerAddress response = client.post()
                    .uri("/api/v1/internal/users/checkout-address-resolution")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(Map.of("subject", subject, "addressId", addressId))
                    .retrieve()
                    .body(BuyerAddress.class);
            if (response == null) {
                throw unavailable();
            }
            return response;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_ADDRESS_NOT_FOUND",
                    "The selected address is unavailable.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public String resolveBuyer(String subject) {
        try {
            BuyerResolution response = client.post()
                    .uri("/api/v1/internal/users/checkout-buyer-resolution")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(Map.of("subject", subject))
                    .retrieve()
                    .body(BuyerResolution.class);
            if (response == null || response.buyerId() == null) {
                throw unavailable();
            }
            return response.buyerId();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_NOT_FOUND",
                    "Checkout was not found.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public void requireCapability(String userId, String scope) {
        try {
            CapabilityResponse response = client.post()
                    .uri("/api/v1/internal/users/capabilities/evaluate")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header(CorrelationId.HEADER_NAME, correlationId())
                    .body(Map.of("userId", userId, "scopes", List.of(scope)))
                    .retrieve()
                    .body(CapabilityResponse.class);
            if (response == null || response.decisions() == null || response.decisions().size() != 1
                    || !scope.equals(response.decisions().getFirst().scope())) {
                throw decisionUnavailable();
            }
            CapabilityDecision decision = response.decisions().getFirst();
            if (!decision.allowed()) {
                throw new CheckoutException(
                        HttpStatus.FORBIDDEN,
                        "USER_CAPABILITY_RESTRICTED",
                        "Buying is currently unavailable for this marketplace account.");
            }
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw decisionUnavailable();
        }
    }

    @Override
    public void requireBusinessCapabilities(Set<String> businessIds, String scope) {
        if (businessIds == null || businessIds.isEmpty()) {
            throw decisionUnavailable();
        }
        try {
            BusinessCapabilityBatchResponse response = client.post()
                    .uri("/api/v1/internal/businesses/capabilities/evaluate-batch")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header(CorrelationId.HEADER_NAME, correlationId())
                    .body(Map.of("businessIds", businessIds, "scopes", List.of(scope)))
                    .retrieve()
                    .body(BusinessCapabilityBatchResponse.class);
            if (response == null || response.businesses() == null
                    || response.businesses().size() != businessIds.size()) {
                throw decisionUnavailable();
            }
            Set<String> returned = new HashSet<>();
            for (BusinessCapabilityResponse business : response.businesses()) {
                if (business == null || business.businessId() == null || !returned.add(business.businessId())
                        || business.decisions() == null || business.decisions().size() != 1
                        || !scope.equals(business.decisions().getFirst().scope())) {
                    throw decisionUnavailable();
                }
                if (!business.decisions().getFirst().allowed()) {
                    throw new CheckoutException(HttpStatus.FORBIDDEN, "BUSINESS_CAPABILITY_RESTRICTED",
                            "This business is not accepting new marketplace sales.");
                }
            }
            if (!returned.equals(businessIds)) throw decisionUnavailable();
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw decisionUnavailable();
        }
    }

    private String correlationId() {
        return CorrelationId.acceptOrGenerate(MDC.get("correlationId")).value();
    }

    private CheckoutException unavailable() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHECKOUT_DEPENDENCY_UNAVAILABLE",
                "Buyer address validation is temporarily unavailable.");
    }

    private CheckoutException decisionUnavailable() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ENFORCEMENT_DECISION_UNAVAILABLE",
                "Marketplace capability validation is temporarily unavailable.");
    }

    private record BuyerResolution(String buyerId) {
    }

    private record CapabilityResponse(String userId, String evaluatedAt, List<CapabilityDecision> decisions) {
    }

    private record CapabilityDecision(
            String scope,
            boolean allowed,
            String effectiveAction,
            String enforcementActionId,
            String effectiveAt,
            String expiresAt,
            String supportReference) {
    }

    private record BusinessCapabilityBatchResponse(
            String evaluatedAt, List<BusinessCapabilityResponse> businesses) { }

    private record BusinessCapabilityResponse(
            String businessId, String evaluatedAt, List<CapabilityDecision> decisions) { }
}
