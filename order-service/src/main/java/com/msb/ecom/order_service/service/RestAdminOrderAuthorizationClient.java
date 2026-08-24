package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.order_service.model.AdminOrderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

@Component
public class RestAdminOrderAuthorizationClient implements AdminOrderAuthorizationClient {
    private final RestClient client;

    public RestAdminOrderAuthorizationClient(
            RestClient.Builder builder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.client = builder.baseUrl(authServiceUrl).build();
    }

    @Override
    public Access requireAdmin(String bearerToken) {
        try {
            AdminEnvelope response = client.get()
                    .uri("/api/v1/admin/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, result) -> {
                        if (result.getStatusCode().value() == 401 || result.getStatusCode().value() == 403) {
                            throw forbidden();
                        }
                        throw unavailable();
                    })
                    .body(AdminEnvelope.class);
            if (response == null || response.data() == null) {
                throw unavailable();
            }
            return response.data();
        } catch (AdminOrderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public Labels labels(String bearerToken, Set<String> userIds, Set<String> businessIds) {
        try {
            LabelsEnvelope response = client.get()
                    .uri(builder -> builder.path("/api/v1/admin/order-context")
                            .queryParam("userIds", userIds.toArray())
                            .queryParam("businessIds", businessIds.toArray())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(LabelsEnvelope.class);
            return response == null || response.data() == null
                    ? new Labels(null, null, null)
                    : response.data();
        } catch (RestClientException exception) {
            return new Labels(null, null, null);
        }
    }

    private AdminOrderException forbidden() {
        return new AdminOrderException(HttpStatus.FORBIDDEN, "ADMIN_PERMISSION_REQUIRED",
                "Administrative order permission is required.");
    }

    private AdminOrderException unavailable() {
        return new AdminOrderException(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_AUTHORIZATION_UNAVAILABLE",
                "Administrative authorization is temporarily unavailable.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdminEnvelope(Access data) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LabelsEnvelope(Labels data) { }
}
