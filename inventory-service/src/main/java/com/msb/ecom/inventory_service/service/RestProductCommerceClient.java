package com.msb.ecom.inventory_service.service;

import com.msb.ecom.inventory_service.model.InventoryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestProductCommerceClient implements ProductCommerceClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public RestProductCommerceClient(
            RestClient.Builder restClientBuilder,
            @Value("${service.product.url}") String productServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.restClient = restClientBuilder.baseUrl(productServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public CatalogPage getBusinessItems(
            String businessId,
            String query,
            String status,
            String cursor,
            Integer limit) {
        try {
            CatalogPage response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/api/v1/internal/businesses/{businessId}/store/items/commerce-context");
                        if (query != null && !query.isBlank()) {
                            builder.queryParam("q", query);
                        }
                        if (status != null && !status.isBlank()) {
                            builder.queryParam("status", status);
                        }
                        if (cursor != null && !cursor.isBlank()) {
                            builder.queryParam("cursor", cursor);
                        }
                        if (limit != null) {
                            builder.queryParam("limit", limit);
                        }
                        return builder.build(businessId);
                    })
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw dependencyFailure();
                    })
                    .body(CatalogPage.class);
            if (response == null || response.data() == null || response.page() == null) {
                throw dependencyFailure();
            }
            return response;
        } catch (InventoryException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw dependencyFailure();
        }
    }

    @Override
    public CatalogItem getBusinessItem(String businessId, String listingId) {
        try {
            CatalogItem response = restClient.get()
                    .uri("/api/v1/internal/businesses/{businessId}/store/items/{listingId}/commerce-context",
                            businessId, listingId)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, clientResponse) -> {
                        throw new InventoryException(
                                HttpStatus.NOT_FOUND,
                                "BUSINESS_LISTING_NOT_FOUND",
                                "The business listing was not found.");
                    })
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw dependencyFailure();
                    })
                    .body(CatalogItem.class);
            if (response == null) {
                throw dependencyFailure();
            }
            return response;
        } catch (InventoryException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw dependencyFailure();
        }
    }

    private InventoryException dependencyFailure() {
        return new InventoryException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "INVENTORY_DEPENDENCY_UNAVAILABLE",
                "Catalog validation is temporarily unavailable.");
    }
}
