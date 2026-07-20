package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.BusinessOrderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class RestBusinessOrderAuthorizationClient
        implements BusinessOrderAuthorizationClient {

    private static final String ORDER_VIEW = "ORDER_VIEW";
    private static final String ORDER_FINANCE_VIEW = "ORDER_FINANCE_VIEW";

    private final RestClient client;

    public RestBusinessOrderAuthorizationClient(
            RestClient.Builder builder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.client = builder.baseUrl(authServiceUrl).build();
    }

    RestBusinessOrderAuthorizationClient(RestClient client) {
        this.client = client;
    }

    // Delegates active business membership to Auth while preserving the actor credential.
    @Override
    public Access authorize(String accessToken, String businessId) {
        try {
            MembershipEnvelope envelope = client.get()
                    .uri("/api/v1/businesses/{businessId}/membership/me", businessId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(MembershipEnvelope.class);
            Membership membership = envelope == null ? null : envelope.data();
            if (membership == null
                    || !businessId.equals(membership.businessId())
                    || !"ACTIVE".equals(membership.status())
                    || membership.permissions() == null
                    || !membership.permissions().contains(ORDER_VIEW)) {
                throw notFound();
            }
            return new Access(
                    membership.businessId(),
                    membership.userId(),
                    membership.role(),
                    membership.permissions().contains(ORDER_FINANCE_VIEW));
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw notFound();
            }
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private BusinessOrderException notFound() {
        return new BusinessOrderException(
                HttpStatus.NOT_FOUND,
                "BUSINESS_ORDER_NOT_FOUND",
                "Business order was not found.");
    }

    private BusinessOrderException unavailable() {
        return new BusinessOrderException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BUSINESS_ORDERS_DEPENDENCY_UNAVAILABLE",
                "Business orders are temporarily unavailable.");
    }

    record MembershipEnvelope(Membership data) {
    }

    record Membership(
            String businessId,
            String userId,
            String role,
            String status,
            List<String> permissions
    ) {
    }
}
