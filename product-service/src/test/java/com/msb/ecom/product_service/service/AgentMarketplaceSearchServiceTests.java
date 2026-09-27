package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.dto.AgentMarketplaceAvailabilityResponse;
import com.msb.ecom.product_service.dto.AgentMarketplaceSearchResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.dto.PublicListingSearchPageResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import com.msb.ecom.product_service.search.hybrid.ListingConceptCompatibilityService;
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
    private final ListingConceptCompatibilityService conceptCompatibility =
            mock(ListingConceptCompatibilityService.class);
    private final AuthServiceClient authServiceClient = mock(AuthServiceClient.class);
    private final ListingService listingService = mock(ListingService.class);
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
            conceptCompatibility,
            authServiceClient,
            listingService,
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
    void returnsOnlyConceptCompatibleMySqlRevalidatedAvailableIdsInBm25Order() {
        String firstId = "01L00000000000000000000001";
        String staleId = "01L00000000000000000000002";
        String thirdId = "01L00000000000000000000003";
        when(searchClient.searchRelevantIds(
                org.mockito.ArgumentMatchers.eq("ALL"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(firstId, staleId, thirdId));
        PublicListingResponse first = listing(firstId, "INDIVIDUAL", 1);
        PublicListingResponse third = listing(thirdId, "INDIVIDUAL", 2);
        when(repository.findPublicListingsByIds(List.of(firstId, staleId, thirdId)))
                .thenReturn(List.of(first, third));
        when(conceptCompatibility.productTypeCompatible(
                org.mockito.ArgumentMatchers.eq("desk chair"),
                org.mockito.ArgumentMatchers.any(PublicListingResponse.class)))
                .thenReturn(true);

        AgentMarketplaceSearchResponse result = service.search(TOKEN, request());

        assertEquals(
                List.of(
                        new AgentMarketplaceSearchResponse.Candidate(firstId, 1),
                        new AgentMarketplaceSearchResponse.Candidate(thirdId, 3)),
                result.data());
    }

    @Test
    void excludesProductTypeIncompatibleBm25CandidatesWithoutFillingTopK() {
        String deskId = "01L00000000000000000000001";
        String radioId = "01L00000000000000000000002";
        when(searchClient.searchRelevantIds(
                org.mockito.ArgumentMatchers.eq("ALL"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(deskId, radioId));
        PublicListingResponse desk = listing(deskId, "INDIVIDUAL", 1);
        PublicListingResponse radio = listing(radioId, "BUSINESS", 1);
        when(repository.findPublicListingsByIds(List.of(deskId, radioId)))
                .thenReturn(List.of(desk, radio));
        when(conceptCompatibility.productTypeCompatible("desk chair", desk)).thenReturn(true);
        when(conceptCompatibility.productTypeCompatible("desk chair", radio)).thenReturn(false);

        AgentMarketplaceSearchResponse result = service.search(TOKEN, request());

        assertEquals(
                List.of(new AgentMarketplaceSearchResponse.Candidate(deskId, 1)),
                result.data());
    }

    @Test
    void returnsCompatibleBusinessCandidateOnlyAfterPublicStoreVisibilityCheck() {
        String listingId = "01L00000000000000000000001";
        String businessId = "01B00000000000000000000001";
        when(searchClient.searchRelevantIds(
                org.mockito.ArgumentMatchers.eq("ALL"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(listingId));
        PublicListingResponse listing = listing(listingId, "BUSINESS", 1);
        when(listing.sellerId()).thenReturn(businessId);
        when(repository.findPublicListingsByIds(List.of(listingId)))
                .thenReturn(List.of(listing));
        when(authServiceClient.searchPublicBusinessStores(
                null, java.util.Set.of(businessId), java.util.Set.of()))
                .thenReturn(List.of(new AuthServiceClient.PublicBusinessStoreSearchResult(
                        businessId,
                        "01S00000000000000000000001",
                        "Harbor Store",
                        "Harbor LLC")));
        when(conceptCompatibility.productTypeCompatible("desk chair", listing))
                .thenReturn(true);

        AgentMarketplaceSearchResponse result = service.search(TOKEN, request());

        assertEquals(
                List.of(new AgentMarketplaceSearchResponse.Candidate(listingId, 1)),
                result.data());
    }

    @Test
    void mysqlModeUsesBoundedPublicSearchServicesAndKeepsOnlyCompatibleResults() {
        ListingSearchProperties mysql = new ListingSearchProperties(
                "mysql",
                new ListingSearchProperties.OpenSearchProperties(
                        "http://localhost:9201",
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        true));
        AgentMarketplaceSearchService mysqlService = new AgentMarketplaceSearchService(
                mysql,
                searchClient,
                repository,
                conceptCompatibility,
                authServiceClient,
                listingService,
                meterRegistry,
                TOKEN);
        PublicListingResponse desk = listing(
                "01L00000000000000000000001", "INDIVIDUAL", 1);
        PublicListingResponse radio = listing(
                "01L00000000000000000000002", "INDIVIDUAL", 1);
        PublicListingResponse lamp = listing(
                "01L00000000000000000000003", "BUSINESS", 1);
        when(listingService.searchIndividualMarketplaceListings(
                org.mockito.ArgumentMatchers.any(PublicListingSearchRequest.class)))
                .thenReturn(page(desk, radio));
        when(listingService.searchBusinessStoreListings(
                org.mockito.ArgumentMatchers.any(PublicListingSearchRequest.class)))
                .thenReturn(page(lamp));
        when(conceptCompatibility.productTypeCompatible("desk chair", desk)).thenReturn(true);
        when(conceptCompatibility.productTypeCompatible("desk chair", radio)).thenReturn(false);
        when(conceptCompatibility.productTypeCompatible("desk chair", lamp)).thenReturn(false);

        AgentMarketplaceSearchResponse result = mysqlService.search(TOKEN, request());

        assertEquals(
                List.of(new AgentMarketplaceSearchResponse.Candidate(desk.id(), 1)),
                result.data());
        verify(searchClient, never()).searchRelevantIds(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
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

    private PublicListingSearchPageResponse page(PublicListingResponse... listings) {
        return new PublicListingSearchPageResponse(
                List.of(listings),
                new PublicListingSearchPageResponse.PageMetadata(null, false));
    }
}
