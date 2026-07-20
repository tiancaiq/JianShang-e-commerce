package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.inventory_service.model.InventoryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class RestInventoryAuthorizationClient implements InventoryAuthorizationClient {

    private final RestClient restClient;

    public RestInventoryAuthorizationClient(
            RestClient.Builder restClientBuilder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.restClient = restClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    // Resolves the authenticated business/store context and enforces the requested inventory permission.
    public BusinessAuthorization requirePermission(String bearerToken, String businessId, String permission) {
        try {
            MembershipEnvelope membershipResponse = restClient.get()
                    .uri("/api/v1/businesses/{businessId}/membership/me", businessId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw forbidden();
                    })
                    .body(MembershipEnvelope.class);
            Membership membership = membershipResponse == null ? null : membershipResponse.data();

            StoreContextEnvelope contextResponse = restClient.get()
                    .uri("/api/v1/businesses/me/store-context")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw forbidden();
                    })
                    .body(StoreContextEnvelope.class);

            StoreContext context = contextResponse == null ? null : contextResponse.data();
            if (membership == null
                    || context == null
                    || context.store() == null
                    || !businessId.equals(membership.businessId())
                    || !businessId.equals(context.businessId())
                    || !businessId.equals(context.store().businessId())
                    || !"ACTIVE".equals(membership.status())
                    || !"ACTIVE".equals(context.businessStatus())
                    || !"ACTIVE".equals(context.store().status())
                    || membership.permissions() == null
                    || !membership.permissions().contains(permission)) {
                throw forbidden();
            }
            return new BusinessAuthorization(context.businessId(), membership.userId(), membership.role());
        } catch (InventoryException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new InventoryException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "INVENTORY_DEPENDENCY_UNAVAILABLE",
                    "Business authorization is temporarily unavailable.");
        }
    }

    private InventoryException forbidden() {
        return new InventoryException(
                HttpStatus.FORBIDDEN,
                "BUSINESS_INVENTORY_FORBIDDEN",
                "Inventory access is not allowed for this business.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoreContextEnvelope(StoreContext data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MembershipEnvelope(Membership data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Membership(
            String businessId,
            String userId,
            String role,
            String status,
            List<String> permissions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoreContext(
            String businessId,
            String businessStatus,
            String membershipRole,
            List<String> permissions,
            Store store
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Store(
            String id,
            String businessId,
            String status
    ) {
    }
}
