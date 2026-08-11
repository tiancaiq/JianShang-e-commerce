package com.msb.ecom.payment_service.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "payment.outbox.transport", havingValue = "HTTP_LOCAL_DEMO")
public class LocalDemoHttpPaymentEventTransport implements PaymentEventTransport {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public LocalDemoHttpPaymentEventTransport(
            RestClient.Builder builder,
            @Value("${service.order.url:http://localhost:8081}") String orderServiceUrl,
            @Value("${commerce.internal-service-token:}") String internalServiceToken) {
        this.client = builder.baseUrl(orderServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public boolean available() {
        return !internalServiceToken.isBlank();
    }

    // Publishes only the strict payment v1 envelope to the service-authenticated Order consumer.
    @Override
    public void publish(PaymentEventMessage message) {
        try {
            client.post()
                    .uri("/api/v1/internal/events/payments")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new PaymentEventTransportException("TRANSPORT_UNAVAILABLE");
        }
    }
}
