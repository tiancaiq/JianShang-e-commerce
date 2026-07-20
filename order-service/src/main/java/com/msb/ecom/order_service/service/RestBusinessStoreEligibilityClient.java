package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CartException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Component
public class RestBusinessStoreEligibilityClient implements BusinessStoreEligibilityClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestBusinessStoreEligibilityClient(
            RestClient.Builder builder,
            @Value("${service.auth.url}") String authServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(authServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    // Loads current business/store eligibility without forwarding the buyer credential.
    @Override
    public Optional<Eligibility> find(String businessId, String storeId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri(
                            "/api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility",
                            businessId,
                            storeId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .body(Eligibility.class));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new CartException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CART_DEPENDENCY_UNAVAILABLE",
                    "Seller validation is temporarily unavailable.");
        }
    }
}
