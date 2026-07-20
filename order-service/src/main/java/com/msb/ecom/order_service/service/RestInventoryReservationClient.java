package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class RestInventoryReservationClient implements InventoryReservationClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestInventoryReservationClient(
            RestClient.Builder builder,
            @Value("${service.inventory.url}") String inventoryServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(inventoryServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public Reservation reserve(String checkoutId, Instant expiresAt, List<Line> lines) {
        try {
            InventoryResponse response = client.post()
                    .uri("/api/v1/internal/inventory/reservations")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header("Idempotency-Key", "checkout-reserve:" + checkoutId)
                    .body(Map.of(
                            "checkoutId", checkoutId,
                            "purpose", "CHECKOUT",
                            "expiresAt", expiresAt,
                            "items", lines))
                    .retrieve()
                    .body(InventoryResponse.class);
            return required(response);
        } catch (HttpClientErrorException.Conflict exception) {
            if (exception.getResponseBodyAsString().contains("INVENTORY_INSUFFICIENT_STOCK")) {
                throw new CheckoutException(
                        HttpStatus.CONFLICT,
                        "CHECKOUT_INSUFFICIENT_STOCK",
                        "One or more items no longer have enough stock.");
            }
            throw new CheckoutException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CHECKOUT_RESERVATION_PENDING",
                    "Inventory reservation is still being reconciled.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw pending();
        }
    }

    @Override
    public Reservation get(String reservationId) {
        try {
            return required(client.get()
                    .uri("/api/v1/internal/inventory/reservations/{id}", reservationId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .body(InventoryResponse.class));
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw pending();
        }
    }

    @Override
    public Reservation release(String checkoutId, String reservationId, String reason) {
        try {
            return required(client.post()
                    .uri("/api/v1/internal/inventory/reservations/{id}/release", reservationId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header("Idempotency-Key", "checkout-release:" + checkoutId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(InventoryResponse.class));
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw pending();
        }
    }

    // Commits the checkout reservation with a deterministic idempotency key.
    @Override
    public Reservation commit(String checkoutId, String reservationId, String correlationId) {
        try {
            return required(client.post()
                    .uri("/api/v1/internal/inventory/reservations/{id}/commit", reservationId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header("Idempotency-Key", "order-confirm:" + checkoutId)
                    .header(CorrelationId.HEADER_NAME, CorrelationId.acceptOrGenerate(correlationId).value())
                    .retrieve()
                    .body(InventoryResponse.class));
        } catch (HttpClientErrorException.Conflict exception) {
            String body = exception.getResponseBodyAsString();
            if (body.contains("INVENTORY_RESERVATION_EXPIRED")
                    || body.contains("INVENTORY_RESERVATION_NOT_COMMITTABLE")) {
                throw new com.msb.ecom.order_service.model.OrderConfirmationException(
                        "ORDER_CONFIRMATION_REQUIRES_RECOVERY",
                        "Paid checkout requires inventory recovery.");
            }
            throw new CheckoutException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_INVENTORY_COMMIT_PENDING",
                    "Inventory commit is still being reconciled.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new CheckoutException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_INVENTORY_COMMIT_PENDING",
                    "Inventory commit is still being reconciled.");
        }
    }

    private Reservation required(InventoryResponse response) {
        if (response == null) {
            throw pending();
        }
        return new Reservation(
                response.id(),
                response.checkoutId(),
                response.purpose(),
                response.status(),
                response.usable(),
                response.expiresAt(),
                response.version(),
                response.items() == null
                        ? List.of()
                        : response.items().stream()
                                .map(item -> new Item(item.listingId(), item.quantity()))
                                .toList());
    }

    private CheckoutException pending() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHECKOUT_RESERVATION_PENDING",
                "Inventory reservation is still being reconciled.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryResponse(
            String id,
            String checkoutId,
            String purpose,
            String status,
            boolean usable,
            Instant expiresAt,
            long version,
            List<InventoryItem> items
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InventoryItem(String listingId, int quantity) {
    }
}
