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
    public CurrentUser requireCurrentUser(String bearerToken) {
        CurrentUserEnvelope response = restClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied current user lookup status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Authenticated user is required.");
                })
                .body(CurrentUserEnvelope.class);

        if (response == null
                || response.data() == null
                || response.data().id() == null
                || !"ACTIVE".equals(response.data().status())) {
            log.warn("Auth-service returned inactive or missing current user data status={}",
                    response == null || response.data() == null ? "missing" : response.data().status());
            throw new ListingAuthorizationException("Authenticated user is required.");
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

    @Override
    public BusinessStoreContextAuthorization requireBusinessStoreContext(String bearerToken, String businessId) {
        BusinessStoreContextEnvelope response = restClient.get()
                .uri("/api/v1/businesses/me/store-context")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied business store context status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Active business store context is required.");
                })
                .body(BusinessStoreContextEnvelope.class);

        if (response == null
                || response.data() == null
                || response.data().store() == null
                || !businessId.equals(response.data().businessId())
                || !businessId.equals(response.data().store().businessId())
                || !"ACTIVE".equals(response.data().businessStatus())
                || !"ACTIVE".equals(response.data().store().status())
                || response.data().permissions() == null
                || !response.data().permissions().contains(LISTING_DRAFT_CREATE)) {
            log.warn("Auth-service returned insufficient business store context requestedBusinessId={} returnedBusinessId={}",
                    businessId, response == null || response.data() == null ? "missing" : response.data().businessId());
            throw new ListingAuthorizationException("Active business store context is required.");
        }
        return response.data();
    }

    @Override
    public AdminIdentityLabels lookupPublicSellerLabels(Set<String> userIds, Set<String> businessIds) {
        AdminIdentityLabelsEnvelope response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/public/seller-labels")
                        .queryParam("userIds", userIds.toArray())
                        .queryParam("businessIds", businessIds.toArray())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied public seller label lookup status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Seller labels could not be loaded.");
                })
                .body(AdminIdentityLabelsEnvelope.class);

        if (response == null || response.data() == null) {
            return new AdminIdentityLabels(List.of(), List.of());
        }
        return response.data();
    }

    @Override
    public List<PublicBusinessStoreSearchResult> searchPublicBusinessStores(
            String query,
            Set<String> businessIds,
            Set<String> storeIds) {
        PublicBusinessStoreSearchEnvelope response = restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/api/v1/public/business-stores/search")
                            .queryParam("businessIds", businessIds.toArray())
                            .queryParam("storeIds", storeIds.toArray());
                    if (query != null && !query.isBlank()) {
                        builder.queryParam("q", query);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service denied public business store search status={}",
                            clientResponse.getStatusCode().value());
                    throw new ListingAuthorizationException("Business store visibility could not be verified.");
                })
                .body(PublicBusinessStoreSearchEnvelope.class);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IndividualSellerEnvelope(IndividualSellerAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUserEnvelope(CurrentUser data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BusinessMembershipEnvelope(BusinessMembershipAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BusinessStoreContextEnvelope(BusinessStoreContextAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlatformAdminEnvelope(PlatformAdminAuthorization data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdminIdentityLabelsEnvelope(AdminIdentityLabels data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PublicBusinessStoreSearchEnvelope(List<PublicBusinessStoreSearchResult> data) {
    }
}
