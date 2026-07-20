package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CartException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestInventoryAvailabilityClient implements InventoryAvailabilityClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestInventoryAvailabilityClient(
            RestClient.Builder builder,
            @Value("${service.inventory.url}") String inventoryServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(inventoryServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    // Loads the authoritative available balance for one listing.
    @Override
    public Availability get(String listingId) {
        try {
            Availability response = client.get()
                    .uri("/api/v1/internal/inventory/{listingId}/availability", listingId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .body(Availability.class);
            if (response == null) {
                throw unavailable();
            }
            return response;
        } catch (CartException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private CartException unavailable() {
        return new CartException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CART_DEPENDENCY_UNAVAILABLE",
                "Inventory validation is temporarily unavailable.");
    }
}
