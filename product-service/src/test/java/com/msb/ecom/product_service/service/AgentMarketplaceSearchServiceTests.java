package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.dto.AgentMarketplaceAvailabilityResponse;
import com.msb.ecom.product_service.dto.AgentMarketplaceSearchResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMarketplaceSearchServiceTests {

    private static final String TOKEN = "offline-service-token";

    private final OpenSearchListingSearchClient searchClient = mock(OpenSearchListingSearchClient.class);
    private final ListingDraftRepository repository = mock(ListingDraftRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AgentMarketplaceSearchService service = new AgentMarketplaceSearchService(
            new ListingSearchProperties(
                    "opensearch",
                    new ListingSearchProperties.OpenSearchProperties(
                            "http://localhost:9201",
                            "marketplace-listings",
                            "marketplace-listings-write",
                            "marketplace-listings-v1",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(3),
                            true)),
            searchClient,
            repository,
            meterRegistry,
            TOKEN);

    @Test
    void rejectsInvalidServiceTokenBeforeSearchOrDatabaseWork() {
        PublicListingSearchRequest request = request();

        assertThrows(ListingAuthorizationException.class, () -> service.search("wrong", request));

        verify(searchClient, never()).searchRelevantIds(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).findPublicListingsByIds(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void returnsOnlyMySqlRevalidatedIndividualAvailableIdsInBm25Order() {
        String firstId = "01L00000000000000000000001";
        String staleId = "01L00000000000000000000002";
        String thirdId = "01L00000000000000000000003";
        when(searchClient.searchRelevantIds(
                org.mockito.ArgumentMatchers.eq("INDIVIDUAL"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(firstId, staleId, thirdId));
        PublicListingResponse first = listing(firstId, "INDIVIDUAL", 1);
        PublicListingResponse third = listing(thirdId, "INDIVIDUAL", 2);
        when(repository.findPublicListingsByIds(List.of(firstId, staleId, thirdId)))
                .thenReturn(List.of(first, third));

        AgentMarketplaceSearchResponse result = service.search(TOKEN, request());

        assertEquals(
                List.of(
                        new AgentMarketplaceSearchResponse.Candidate(firstId, 1),
                        new AgentMarketplaceSearchResponse.Candidate(thirdId, 3)),
                result.data());
    }

    @Test
    void availabilityProbeAuthenticatesBeforeAuthoritativeInventoryRead() {
        assertThrows(
                ListingAuthorizationException.class,
                () -> service.availability("wrong", "laptop", 1));

        verify(repository, never()).countActiveIndividualInventory(
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void availabilityProbeReturnsStrictBroadProductOwnedCount() {
        when(repository.countActiveIndividualInventory("laptop")).thenReturn(7L);

        AgentMarketplaceAvailabilityResponse response =
                service.availability(TOKEN, "  Laptop  ", 1);

        assertEquals(AgentMarketplaceAvailabilityResponse.SCHEMA_VERSION, response.schemaVersion());
        assertEquals(AgentMarketplaceAvailabilityResponse.MODE, response.mode());
        assertEquals("laptop", response.category());
        assertEquals(7L, response.totalActiveCategoryInventory());
        assertEquals(0L, response.relatedCategoryMatches());
        assertEquals(null, response.failureReason());
        assertEquals(false, response.retryable());
        assertEquals(true, response.searchExecuted());
        assertEquals(1.0, meterRegistry.get(
                "product.agent.marketplace.availability.operations")
                .tag("result", "available").counter().count());
    }

    @Test
    void availabilityProbeRejectsFiltersOrNonProbeLimits() {
        assertThrows(IllegalArgumentException.class, () -> service.availability(TOKEN, " ", 1));
        assertThrows(IllegalArgumentException.class, () -> service.availability(TOKEN, "laptop", 2));

        verify(repository, never()).countActiveIndividualInventory(
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void availabilityProbeRecordsOnlyFixedFailureTelemetry() {
        when(repository.countActiveIndividualInventory("laptop"))
                .thenThrow(new RuntimeException("private database detail"));

        assertThrows(RuntimeException.class, () -> service.availability(TOKEN, "laptop", 1));

        assertEquals(1.0, meterRegistry.get(
                "product.agent.marketplace.availability.operations")
                .tag("result", "failure").counter().count());
    }

    private PublicListingResponse listing(String id, String sellerType, int quantity) {
        PublicListingResponse listing = mock(PublicListingResponse.class);
        when(listing.id()).thenReturn(id);
        when(listing.sellerType()).thenReturn(sellerType);
        when(listing.quantity()).thenReturn(quantity);
        return listing;
    }

    private PublicListingSearchRequest request() {
        return new PublicListingSearchRequest(
                "desk chair",
                null,
                "GOOD",
                null,
                null,
                "Irvine",
                null,
                "newest",
                null,
                20);
    }
}
