package com.msb.ecom.notification_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class RestNotificationBusinessAccessClient implements NotificationBusinessAccessClient {
    private final RestClient client;

    public RestNotificationBusinessAccessClient(
            RestClient.Builder builder,
            @Value("${notifications.read-api.auth-service-url}") String authServiceUrl) {
        this.client = builder.baseUrl(authServiceUrl).build();
    }

    @Override
    public void requireNotificationAccess(
            String authorization, String correlationId, String businessId) {
        try {
            Envelope envelope = client.get()
                    .uri("/api/v1/businesses/{businessId}/membership/me", businessId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header("X-Correlation-Id", correlationId)
                    .retrieve().body(Envelope.class);
            Membership membership = envelope == null ? null : envelope.data();
            if (membership == null || !businessId.equals(membership.businessId())
                    || !"ACTIVE".equals(membership.status()) || membership.permissions() == null
                    || (!membership.permissions().contains("ORDER_VIEW")
                        && !membership.permissions().contains("ORDER_FULFILL"))) {
                throw new AccessDeniedException();
            }
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND
                    || exception.getStatusCode() == HttpStatus.FORBIDDEN
                    || exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AccessDeniedException();
            }
            throw new DependencyUnavailableException();
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException();
        }
    }

    record Envelope(Membership data) {}
    record Membership(String businessId, String userId, String status, List<String> permissions) {}
    public static final class AccessDeniedException extends RuntimeException {}
    public static final class DependencyUnavailableException extends RuntimeException {}
}
