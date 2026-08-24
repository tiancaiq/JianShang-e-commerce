package com.msb.ecom.order_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestAdminOrderListingContextClient implements AdminOrderListingContextClient {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalToken;

    public RestAdminOrderListingContextClient(
            RestClient.Builder builder,
            @Value("${service.product.url}") String productServiceUrl,
            @Value("${commerce.internal-service-token}") String internalToken) {
        this.client = builder.baseUrl(productServiceUrl).build();
        this.internalToken = internalToken;
    }

    @Override
    public Context find(String listingId) {
        try {
            return client.get()
                    .uri("/api/v1/internal/reports/listings/{listingId}", listingId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .retrieve()
                    .body(Context.class);
        } catch (RestClientException exception) {
            return null;
        }
    }
}
