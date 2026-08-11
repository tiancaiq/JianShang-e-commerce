package com.msb.ecom.order_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class RestCancellationInventoryClient implements CancellationInventoryClient {

    private final RestClient client;
    private final String token;

    public RestCancellationInventoryClient(
            RestClient.Builder builder,
            @Value("${service.inventory.url}") String baseUrl,
            @Value("${commerce.internal-service-token}") String token) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    @Override
    public void restock(
            String reservationId, String orderId, String cancellationRequestId, String key) {
        try {
            client.post()
                    .uri("/api/v1/internal/inventory/reservations/{id}/cancellation-restocks",
                            reservationId)
                    .header("X-Internal-Service-Token", token)
                    .header("Idempotency-Key", key)
                    .body(Map.of("orderId", orderId,
                            "cancellationRequestId", cancellationRequestId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            throw new IllegalStateException("Inventory cancellation recovery failed.", exception);
        }
    }
}
