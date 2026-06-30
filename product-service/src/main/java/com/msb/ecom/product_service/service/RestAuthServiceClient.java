package com.msb.ecom.product_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class RestAuthServiceClient implements AuthServiceClient {

    private static final String LISTING_DRAFT_CREATE = "LISTING_DRAFT_CREATE";

    private final RestClient restClient;

    public RestAuthServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.restClient = restClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    public IndividualSellerAuthorization requireActiveIndividualSeller(String bearerToken) {
        IndividualSellerEnvelope response = restClient.get()
                .uri("/api/v1/individual-seller/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied individual seller authorization status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Active individual seller profile is required.");
                })
                .body(IndividualSellerEnvelope.class);

        if (response == null || response.data() == null || !"ACTIVE".equals(response.data().status())) {
            log.warn("Auth-service returned inactive individual seller authorization status={}",
                    response == null || response.data() == null ? "missing" : response.data().status());
            throw new ListingAuthorizationException("Active individual seller profile is required.");
        }
        return response.data();
    }

    @Override
    public BusinessMembershipAuthorization requireBusinessListingPermission(String bearerToken, String businessId) {
        BusinessMembershipEnvelope response = restClient.get()
                .uri("/api/v1/businesses/{businessId}/membership/me", businessId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied business listing authorization businessId={} status={}",
                            businessId, clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Active business listing permission is required.");
                })
                .body(BusinessMembershipEnvelope.class);

        if (response == null
                || response.data() == null
                || !"ACTIVE".equals(response.data().status())
                || !response.data().permissions().contains(LISTING_DRAFT_CREATE)) {
            log.warn("Auth-service returned insufficient business listing authorization businessId={} status={}",
                    businessId, response == null || response.data() == null ? "missing" : response.data().status());
            throw new ListingAuthorizationException("Active business listing permission is required.");
        }
        return response.data();
    }

    @Override
    public PlatformAdminAuthorization requirePlatformAdmin(String bearerToken) {
        PlatformAdminEnvelope response = restClient.get()
                .uri("/api/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied platform admin authorization status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Platform admin access is required.");
                })
                .body(PlatformAdminEnvelope.class);

        if (response == null
                || response.data() == null
                || !"PLATFORM_ADMIN".equals(response.data().role())) {
            log.warn("Auth-service returned insufficient platform admin authorization role={}",
                    response == null || response.data() == null ? "missing" : response.data().role());
            throw new ListingAuthorizationException("Platform admin access is required.");
        }
        return response.data();
    }

    @Override
    public AdminIdentityLabels lookupAdminIdentityLabels(String bearerToken, Set<String> userIds, Set<String> businessIds) {
        AdminIdentityLabelsEnvelope response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/admin/identity-labels")
                        .queryParam("userIds", userIds.toArray())
                        .queryParam("businessIds", businessIds.toArray())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied admin identity label lookup status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Platform admin access is required.");
                })
                .body(AdminIdentityLabelsEnvelope.class);

        if (response == null || response.data() == null) {
            return new AdminIdentityLabels(List.of(), List.of());
        }
        return response.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IndividualSellerEnvelope(IndividualSellerAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BusinessMembershipEnvelope(BusinessMembershipAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlatformAdminEnvelope(PlatformAdminAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdminIdentityLabelsEnvelope(AdminIdentityLabels data) {
    }
}
