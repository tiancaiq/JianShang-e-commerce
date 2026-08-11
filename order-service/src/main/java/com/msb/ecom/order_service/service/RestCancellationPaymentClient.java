package com.msb.ecom.order_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class RestCancellationPaymentClient implements CancellationPaymentClient {

    private final RestClient client;
    private final String token;

    public RestCancellationPaymentClient(
            RestClient.Builder builder,
            @Value("${service.payment.url}") String baseUrl,
            @Value("${service.payment.internal-service-token}") String token) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    @Override
    public RefundResult refund(
            String paymentIntentId, String orderId, String cancellationRequestId, String key) {
        try {
            Map<?, ?> body = client.post()
                    .uri("/api/v1/internal/payment-intents/{id}/refunds", paymentIntentId)
                    .header("X-Internal-Service-Token", token)
                    .header("Idempotency-Key", key)
                    .body(Map.of("orderId", orderId,
                            "cancellationRequestId", cancellationRequestId))
                    .retrieve().body(Map.class);
            Object refundId = body == null ? null : body.get("refundId");
            Object providerReference = body == null ? null : body.get("providerReference");
            if (!(refundId instanceof String refundIdValue) || refundIdValue.isBlank()
                    || !(providerReference instanceof String providerReferenceValue)
                    || providerReferenceValue.isBlank()) {
                throw new IllegalStateException("Payment refund response was incomplete.");
            }
            return new RefundResult(refundIdValue, providerReferenceValue);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Payment cancellation refund failed.", exception);
        }
    }
}
