package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.product_service.dto.AgentMarketplaceAvailabilityResponse;
import com.msb.ecom.product_service.dto.AgentMarketplaceSearchResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.ListingSearchUnavailableException;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentMarketplaceSearchService {

    private static final int MAX_CANDIDATES = 40;
    private static final Pattern BROAD_CATEGORY = Pattern.compile(
            "^[\\p{L}\\p{N}][\\p{L}\\p{N} .'-]{0,79}$");

    private final ListingSearchProperties searchProperties;
    private final OpenSearchListingSearchClient searchClient;
    private final ListingDraftRepository listingRepository;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;

    public AgentMarketplaceSearchService(
            ListingSearchProperties searchProperties,
            OpenSearchListingSearchClient searchClient,
            ListingDraftRepository listingRepository,
            MeterRegistry meterRegistry,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        this.searchProperties = searchProperties;
        this.searchClient = searchClient;
        this.listingRepository = listingRepository;
        this.meterRegistry = meterRegistry;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Returns BM25-ranked public IDs only after constant-time service auth and MySQL visibility revalidation.
    public AgentMarketplaceSearchResponse search(
            String suppliedToken,
            PublicListingSearchRequest request) {
        requireInternalToken(suppliedToken);
        if (!searchProperties.openSearchEnabled()) {
            throw new ListingSearchUnavailableException("The listing search projection is disabled.");
        }
        PublicListingSearchCriteria criteria = PublicListingSearchRequests.criteria(request);
        if (criteria.keyword() == null) {
            throw new IllegalArgumentException("Search keyword is required for Agent marketplace retrieval.");
        }
        int limit = Math.min(
                PublicListingSearchRequests.normalizedLimit(request.limit(), 20),
                MAX_CANDIDATES);
        List<String> rankedIds = searchClient.searchRelevantIds("INDIVIDUAL", criteria, limit);
        Map<String, PublicListingResponse> eligibleById = listingRepository.findPublicListingsByIds(rankedIds).stream()
                .filter(listing -> "INDIVIDUAL".equals(listing.sellerType()))
                .filter(listing -> listing.quantity() > 0)
                .collect(Collectors.toMap(PublicListingResponse::id, Function.identity()));
        List<AgentMarketplaceSearchResponse.Candidate> candidates = java.util.stream.IntStream
                .range(0, rankedIds.size())
                .filter(index -> eligibleById.containsKey(rankedIds.get(index)))
                .mapToObj(index -> new AgentMarketplaceSearchResponse.Candidate(rankedIds.get(index), index + 1))
                .toList();
        log.info("Agent marketplace lexical retrieval completed result=success candidateCount={}", candidates.size());
        return new AgentMarketplaceSearchResponse(candidates);
    }

    @Transactional(readOnly = true)
    // Answers only broad active-inventory availability from Product's authoritative MySQL state.
    public AgentMarketplaceAvailabilityResponse availability(
            String suppliedToken,
            String category,
            Integer limit) {
        requireInternalToken(suppliedToken);
        String normalized = TextInputs.collapseWhitespaceToNull(category);
        if (normalized == null
                || !BROAD_CATEGORY.matcher(normalized).matches()
                || limit == null
                || limit != 1) {
            throw new IllegalArgumentException("Availability probe category or limit is invalid.");
        }
        String broadCategory = normalized.toLowerCase(Locale.ROOT);
        long total;
        try {
            total = listingRepository.countActiveIndividualInventory(broadCategory);
        } catch (RuntimeException error) {
            meterRegistry.counter(
                    "product.agent.marketplace.availability.operations",
                    "result", "failure").increment();
            log.warn("Agent marketplace availability probe completed result=failure");
            throw error;
        }
        meterRegistry.counter(
                "product.agent.marketplace.availability.operations",
                "result", total > 0 ? "available" : "unavailable").increment();
        log.info("Agent marketplace availability probe completed result=success available={}", total > 0);
        return new AgentMarketplaceAvailabilityResponse(
                AgentMarketplaceAvailabilityResponse.SCHEMA_VERSION,
                AgentMarketplaceAvailabilityResponse.MODE,
                true,
                broadCategory,
                total,
                0,
                null,
                false);
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Agent source authentication is required.");
        }
    }
}
