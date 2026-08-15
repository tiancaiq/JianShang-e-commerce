package com.msb.ecom.order_service.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpNotificationEventTransport implements NotificationEventTransport {
    private final RestClient client;
    private final String token;

    public HttpNotificationEventTransport(
            RestClient.Builder builder,
            @Value("${service.notification.url}") String baseUrl,
            @Value("${commerce.internal-service-token}") String token) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    @Override
    public void send(CommerceNotificationEvent event) {
        client.post().uri("/api/v1/internal/notification-events")
                .header("X-Internal-Service-Token", token)
                .body(event).retrieve().toBodilessEntity();
    }
}
