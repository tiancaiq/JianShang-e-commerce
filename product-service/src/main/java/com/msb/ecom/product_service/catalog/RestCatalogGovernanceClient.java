package com.msb.ecom.product_service.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

@Component
final class RestCatalogGovernanceClient implements CatalogGovernanceClient {
    private final RestClient client;

    RestCatalogGovernanceClient(RestClient.Builder builder,
                                @Value("${service.auth.url}") String authUrl) {
        this.client = builder.baseUrl(authUrl).build();
    }

    @Override
    public CatalogApproval evaluateCategoryDisable(String accessToken, CategorySummary category,
                                                   ImpactPreview impact,
                                                   ChangeCategoryStatusRequest request,
                                                   String idempotencyKey) {
        try {
            CatalogApproval result = client.post()
                    .uri("/api/v1/admin/governance/domain/catalog-category-disable")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new CategoryGovernanceRequest(category.id(), request.status().name(),
                            impact.counts().activeListings(), impact.counts().draftListings(),
                            impact.counts().pendingListings(), impact.categoryVersion(),
                            request.reason(), idempotencyKey, category.name()))
                    .retrieve().body(CatalogApproval.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private CatalogException unavailable() {
        return new CatalogException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "CATALOG_GOVERNANCE_UNAVAILABLE",
                "Catalog governance could not be evaluated. No category change was made.");
    }

    private record CategoryGovernanceRequest(String categoryId, String proposedStatus,
                                             long activeListingCount, long draftListingCount,
                                             long pendingListingCount, long expectedCategoryVersion,
                                             String reason, String idempotencyKey,
                                             String safeCategoryLabel) { }
}
