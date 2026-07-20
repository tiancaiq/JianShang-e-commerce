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
public class RestProductCommerceClient implements ProductCommerceClient {

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
}
