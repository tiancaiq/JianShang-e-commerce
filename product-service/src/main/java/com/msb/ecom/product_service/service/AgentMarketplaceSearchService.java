package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.product_service.dto.AgentMarketplaceAvailabilityResponse;
import com.msb.ecom.product_service.dto.AgentMarketplaceSearchResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchPageResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import com.msb.ecom.product_service.search.hybrid.ListingConceptCompatibilityService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentMarketplaceSearchService {

    private static final int MAX_CANDIDATES = 40;
    private static final Pattern BROAD_CATEGORY = Pattern.compile(
            "^[\\p{L}\\p{N}][\\p{L}\\p{N} &.'-]{0,79}$");
    private static final Pattern GENERIC_SEARCH_SUFFIX = Pattern.compile(
            "(?i)\\s+(?:items|listings|products)$");
    private static final Pattern BUSINESS_SEARCH_INTENT = Pattern.compile(
            "(?i)(?:^|\\s)(?:business|store|shop)(?:\\s|$)");

    private final ListingSearchProperties searchProperties;
    private final OpenSearchListingSearchClient searchClient;
    private final ListingDraftRepository listingRepository;
    private final ListingConceptCompatibilityService conceptCompatibility;
    private final AuthServiceClient authServiceClient;
    private final ListingService listingService;
    private final MeterRegistry meterRegistry;
    private final String internalServiceToken;

    public AgentMarketplaceSearchService(
            ListingSearchProperties searchProperties,
            OpenSearchListingSearchClient searchClient,
            ListingDraftRepository listingRepository,
            ListingConceptCompatibilityService conceptCompatibility,
            AuthServiceClient authServiceClient,
            ListingService listingService,
            MeterRegistry meterRegistry,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        this.searchProperties = searchProperties;
        this.searchClient = searchClient;
        this.listingRepository = listingRepository;
        this.conceptCompatibility = conceptCompatibility;
        this.authServiceClient = authServiceClient;
        this.listingService = listingService;
        this.meterRegistry = meterRegistry;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Returns BM25-ranked public IDs only after constant-time service auth and MySQL visibility revalidation.
    public AgentMarketplaceSearchResponse search(
            String suppliedToken,
            PublicListingSearchRequest request) {
        requireInternalToken(suppliedToken);
        String query = request.q() == null ? null
                : GENERIC_SEARCH_SUFFIX.matcher(request.q().trim()).replaceFirst("");
        PublicListingSearchRequest effectiveRequest = new PublicListingSearchRequest(
                query, request.categoryId(), request.condition(), request.minPrice(),
                request.maxPrice(), request.city(), request.county(), request.sort(),
                request.cursor(), request.limit());
        PublicListingSearchCriteria criteria = PublicListingSearchRequests.criteria(effectiveRequest);
        if (criteria.keyword() == null) {
            throw new IllegalArgumentException("Search keyword is required for Agent marketplace retrieval.");
        }
        int limit = Math.min(
                PublicListingSearchRequests.normalizedLimit(request.limit(), 20),
                MAX_CANDIDATES);
        List<String> rankedIds;
        List<PublicListingResponse> revalidated;
        int candidateLimit = Math.min(MAX_CANDIDATES, Math.max(limit * 4, limit));
        PublicListingSearchRequest candidateRequest = new PublicListingSearchRequest(
                effectiveRequest.q(), request.categoryId(), request.condition(), request.minPrice(),
                request.maxPrice(), request.city(), request.county(), "newest", null,
                candidateLimit);
        if (searchProperties.openSearchEnabled()) {
            List<String> indexedIds = searchClient.searchRelevantIds("ALL", criteria, limit);
            List<PublicListingResponse> indexed = visibleBusinessListings(
                    listingRepository.findPublicListingsByIds(indexedIds));
            // Business storefront search is already MySQL-backed; merge it so projection lag
            // cannot hide otherwise public business listings from the Agent.
            List<PublicListingResponse> storefront = listingService
                    .searchBusinessStoreListings(candidateRequest).data();
            Map<String, PublicListingResponse> ordered = new LinkedHashMap<>();
            Set<String> ranked = new LinkedHashSet<>();
            if (BUSINESS_SEARCH_INTENT.matcher(criteria.keyword()).find()) {
                storefront.forEach(listing -> ordered.putIfAbsent(listing.id(), listing));
                storefront.forEach(listing -> ranked.add(listing.id()));
            }
            ranked.addAll(indexedIds);
            indexed.forEach(listing -> ordered.putIfAbsent(listing.id(), listing));
            storefront.forEach(listing -> ordered.putIfAbsent(listing.id(), listing));
            storefront.forEach(listing -> ranked.add(listing.id()));
            revalidated = new ArrayList<>(ordered.values());
            rankedIds = new ArrayList<>(ranked);
        } else {
            PublicListingSearchPageResponse individual =
                    listingService.searchIndividualMarketplaceListings(candidateRequest);
            PublicListingSearchPageResponse business =
                    listingService.searchBusinessStoreListings(candidateRequest);
            Map<String, PublicListingResponse> ordered = new java.util.LinkedHashMap<>();
            individual.data().forEach(listing -> ordered.putIfAbsent(listing.id(), listing));
            business.data().forEach(listing -> ordered.putIfAbsent(listing.id(), listing));
            revalidated = ordered.values().stream().limit(MAX_CANDIDATES).toList();
            rankedIds = revalidated.stream().map(PublicListingResponse::id).toList();
        }
        Map<String, PublicListingResponse> eligibleById = revalidated.stream()
                .filter(listing -> listing.quantity() > 0)
                .filter(listing -> conceptCompatibility.productTypeCompatible(
                        criteria.keyword(), listing))
                .collect(Collectors.toMap(PublicListingResponse::id, Function.identity()));
        List<AgentMarketplaceSearchResponse.Candidate> candidates = java.util.stream.IntStream
                .range(0, rankedIds.size())
                .filter(index -> eligibleById.containsKey(rankedIds.get(index)))
                .mapToObj(index -> new AgentMarketplaceSearchResponse.Candidate(rankedIds.get(index), index + 1))
                .limit(limit)
                .toList();
        log.info("Agent marketplace lexical retrieval completed result=success candidateCount={}", candidates.size());
        return new AgentMarketplaceSearchResponse(candidates);
    }

    // Business candidates fail closed unless Auth confirms their public storefront visibility.
    private List<PublicListingResponse> visibleBusinessListings(
            List<PublicListingResponse> listings) {
        Set<String> businessIds = listings.stream()
                .filter(listing -> "BUSINESS".equals(listing.sellerType()))
                .map(PublicListingResponse::sellerId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (businessIds.isEmpty()) {
            return listings;
        }
        Set<String> visibleBusinessIds;
        try {
            List<AuthServiceClient.PublicBusinessStoreSearchResult> stores =
                    authServiceClient.searchPublicBusinessStores(null, businessIds, Set.of());
            visibleBusinessIds = (stores == null
                    ? List.<AuthServiceClient.PublicBusinessStoreSearchResult>of()
                    : stores).stream()
                    .map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (RuntimeException exception) {
            log.warn("Agent marketplace business visibility lookup unavailable; hiding business candidates.");
            visibleBusinessIds = Set.of();
        }
        Set<String> allowed = visibleBusinessIds;
        return listings.stream()
                .filter(listing -> !"BUSINESS".equals(listing.sellerType())
                        || allowed.contains(listing.sellerId()))
                .toList();
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
        boolean categoryIdRequested = broadCategory.matches("[0-9a-hjkmnp-tv-z]{26}");
        // Canonical-looking references must resolve in Product's taxonomy, not become keyword probes.
        boolean categorySlugRequested = broadCategory.matches("[a-z0-9]+(?:-[a-z0-9]+)+");
        var resolved = listingRepository.resolveActiveAvailabilityCategory(
                categoryIdRequested ? broadCategory.toUpperCase(Locale.ROOT) : broadCategory);
        if ((categoryIdRequested || categorySlugRequested) && resolved.isEmpty()) {
            throw new CategoryNotFoundException();
        }
        String categoryId = resolved.map(ListingDraftRepository.AvailabilityCategory::id).orElse(null);
        String canonicalCategory = resolved.map(ListingDraftRepository.AvailabilityCategory::slug)
                .orElse(broadCategory);
        long total;
        try {
            Set<String> businessIds = listingRepository.findAvailabilityBusinessIds(
                    categoryId, broadCategory);
            Set<String> visibleBusinessIds = businessIds.isEmpty() ? Set.of()
                    : authServiceClient.searchPublicBusinessStores(null, businessIds, Set.of())
                    .stream().map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            total = listingRepository.countActiveMarketplaceInventory(
                    categoryId, broadCategory, visibleBusinessIds);
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
                canonicalCategory,
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
