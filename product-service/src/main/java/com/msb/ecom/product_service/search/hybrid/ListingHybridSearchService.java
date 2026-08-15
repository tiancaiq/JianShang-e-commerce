package com.msb.ecom.product_service.search.hybrid;

import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ListingHybridSearchService {

    private static final Logger log =
            LoggerFactory.getLogger(ListingHybridSearchService.class);

    static final String INDIVIDUAL_TRANSACTION_NOTICE =
            "Payment and delivery are arranged directly by participants. "
                    + "The platform does not verify or protect off-platform payment.";

    private final ListingHybridSearchProperties properties;
    private final ListingSearchProperties searchProperties;
    private final ListingHybridSearchRequestParser parser;
    private final OpenSearchListingHybridSearchClient searchClient;
    private final ListingHybridSearchRepository repository;
    private final ListingHybridSearchMetrics metrics;
    private final ListingConceptCompatibilityReranker conceptReranker;
    private final String internalServiceToken;
    private final Clock clock;

    @Autowired
    public ListingHybridSearchService(
            ListingHybridSearchProperties properties,
            ListingSearchProperties searchProperties,
            ListingHybridSearchRequestParser parser,
            OpenSearchListingHybridSearchClient searchClient,
            ListingHybridSearchRepository repository,
            ListingHybridSearchMetrics metrics,
            ListingConceptRankingProperties conceptProperties,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        this(
                properties,
                searchProperties,
                parser,
                searchClient,
                repository,
                metrics,
                conceptProperties,
                internalServiceToken,
                Clock.systemUTC());
    }

    ListingHybridSearchService(
            ListingHybridSearchProperties properties,
            ListingSearchProperties searchProperties,
            ListingHybridSearchRequestParser parser,
            OpenSearchListingHybridSearchClient searchClient,
            ListingHybridSearchRepository repository,
            ListingHybridSearchMetrics metrics,
            ListingConceptRankingProperties conceptProperties,
            String internalServiceToken,
            Clock clock) {
        this.properties = properties;
        this.searchProperties = searchProperties;
        this.parser = parser;
        this.searchClient = searchClient;
        this.repository = repository;
        this.metrics = metrics;
        this.conceptReranker = new ListingConceptCompatibilityReranker(conceptProperties);
        this.internalServiceToken = internalServiceToken;
        this.clock = clock;
    }

    ListingHybridSearchService(
            ListingHybridSearchProperties properties,
            ListingSearchProperties searchProperties,
            ListingHybridSearchRequestParser parser,
            OpenSearchListingHybridSearchClient searchClient,
            ListingHybridSearchRepository repository,
            ListingHybridSearchMetrics metrics,
            String internalServiceToken,
            Clock clock) {
        this(properties, searchProperties, parser, searchClient, repository, metrics,
                new ListingConceptRankingProperties(12, 8, 5, 2, 12, 20, 12, 2),
                internalServiceToken, clock);
    }

    @Transactional(readOnly = true)
    // Authenticates, fuses bounded Product-owned branches, then replaces every projection fact with MySQL truth.
    public ListingHybridSearchResponse search(String suppliedToken, byte[] body) {
        long startedAt = System.nanoTime();
        try {
            return executeSearch(suppliedToken, body);
        } finally {
            metrics.recordLatency(System.nanoTime() - startedAt);
        }
    }

    private ListingHybridSearchResponse executeSearch(String suppliedToken, byte[] body) {
        if (!properties.enabled()) {
            metrics.record("request", "disabled");
            throw new ListingHybridSearchFeatureDisabledException();
        }
        requireInternalToken(suppliedToken);
        ListingHybridSearchRequestParser.ParsedRequest parsed = parser.parse(body);
        if (!parsed.hasApprovedIdentity()) {
            metrics.record("request", "identity_mismatch");
            throw new ListingHybridSearchIdentityMismatchException();
        }
        if (!searchProperties.openSearchEnabled()) {
            metrics.record("request", "unavailable");
            throw new ListingHybridSearchUnavailableException();
        }

        try {
            String target = unavailableStage("alias_validate", searchClient::validateReadAlias);
            BranchResult lexical = branch(
                    "lexical_branch",
                    () -> searchClient.searchLexical(target, parsed.request()));
            BranchResult vector = branch(
                    "vector_branch",
                    () -> searchClient.searchVector(target, parsed.request()));
            Retrieval retrieval = retrieval(lexical, vector);
            List<ListingHybridRrf.FusedCandidate> fused = unavailableStage(
                    "rrf_fusion",
                    () -> ListingHybridRrf.fuse(retrieval.lexicalIds(), retrieval.vectorIds()));
            List<String> fusedIds =
                    fused.stream().map(ListingHybridRrf.FusedCandidate::listingId).toList();
            List<ListingHybridSearchListing> current =
                    unavailableStage("mysql_revalidation",
                            () -> repository.findCurrentEligibleByIds(fusedIds));
            ListingHybridSearchResponse response =
                    response(parsed.request(), retrieval, fused, current);
            metrics.record("request", retrieval.degraded() ? "degraded" : "success");
            metrics.recordCount("candidates", fused.size());
            metrics.recordCount("results", response.data().size());
            return response;
        } catch (ListingHybridSearchIdentityMismatchException exception) {
            metrics.record("request", "identity_mismatch");
            throw exception;
        } catch (ListingHybridSearchUnavailableException | DataAccessException exception) {
            metrics.record("request", "unavailable");
            throw new ListingHybridSearchUnavailableException();
        }
    }

    private Retrieval retrieval(BranchResult lexical, BranchResult vector) {
        if (lexical.failed() && vector.failed()) {
            throw new ListingHybridSearchUnavailableException();
        }
        if (lexical.failed() || vector.failed()) {
            if (!properties.singleBranchFallbackEnabled()) {
                throw new ListingHybridSearchUnavailableException();
            }
            if (lexical.failed()) {
                metrics.record("lexical_branch", "failure");
                metrics.record("vector_branch", "success");
                return new Retrieval(List.of(), vector.ids(), "VECTOR_ONLY", true);
            }
            metrics.record("lexical_branch", "success");
            metrics.record("vector_branch", "failure");
            return new Retrieval(lexical.ids(), List.of(), "LEXICAL_ONLY", true);
        }
        metrics.record("lexical_branch", "success");
        metrics.record("vector_branch", "success");
        return new Retrieval(lexical.ids(), vector.ids(), "HYBRID", false);
    }

    private BranchResult branch(String stage, BranchSearch operation) {
        try {
            return new BranchResult(operation.search(), false);
        } catch (ListingHybridSearchUnavailableException exception) {
            recordFailureStage(stage, exception.kind());
            return new BranchResult(List.of(), true);
        }
    }

    private void unavailableStage(String stage, StageOperation operation) {
        unavailableStage(stage, () -> {
            operation.run();
            return null;
        });
    }

    private <T> T unavailableStage(String stage, StageSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ListingHybridSearchIdentityMismatchException exception) {
            throw exception;
        } catch (ListingHybridSearchUnavailableException exception) {
            recordFailureStage(stage, exception.kind());
            throw new ListingHybridSearchUnavailableException(exception.kind());
        } catch (DataAccessException exception) {
            recordFailureStage(stage, ListingHybridSearchFailureKind.INTERNAL_UNAVAILABLE);
            throw new ListingHybridSearchUnavailableException();
        }
    }

    private void recordFailureStage(String stage, ListingHybridSearchFailureKind kind) {
        if (!allowlistedStage(stage)) {
            metrics.record("failure_stage", "unknown");
            return;
        }
        metrics.record("failure_stage", stage);
        ListingHybridSearchFailureKind safeKind = kind == null
                ? ListingHybridSearchFailureKind.INTERNAL_UNAVAILABLE
                : kind;
        log.warn("eventCode=PRODUCT_HYBRID_FAILURE_STAGE stage={} kind={}",
                stage,
                safeKind.logValue());
    }

    private boolean allowlistedStage(String stage) {
        return switch (stage) {
            case "alias_validate",
                 "lexical_branch",
                 "vector_branch",
                 "rrf_fusion",
                 "mysql_revalidation" -> true;
            default -> false;
        };
    }

    private ListingHybridSearchResponse response(
            ListingHybridSearchRequest request,
            Retrieval retrieval,
            List<ListingHybridRrf.FusedCandidate> fused,
            List<ListingHybridSearchListing> current) {
        Map<String, ListingHybridSearchListing> currentById = new HashMap<>();
        for (ListingHybridSearchListing listing : current) {
            currentById.put(listing.listingId(), listing);
        }
        List<RelevantMatch> relevant = new ArrayList<>();
        boolean conceptRerank = ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION.equals(
                request.responseSchemaVersion());
        ListingQueryConcept interpreted = conceptRerank
                ? ListingQueryConcept.interpret(request.query())
                : null;
        int totalMatches = 0;
        int fusedOrder = 0;
        for (ListingHybridRrf.FusedCandidate candidate : fused) {
            ListingHybridSearchListing listing = currentById.get(candidate.listingId());
            if (listing == null || !matches(request.filters(), listing)) {
                continue;
            }
            totalMatches++;
            ListingConceptCompatibilityReranker.Assessment assessment = conceptRerank
                    ? conceptReranker.assess(interpreted, listing)
                    : null;
            if (conceptRerank) {
                if (assessment.relevance()
                        == ListingConceptCompatibilityReranker.Relevance.LOW) {
                    continue;
                }
            } else if (request.filters().categoryId() == null
                    && !ListingDiscoveryFacetBuilder.relevant(request.query(), listing)) {
                continue;
            }
            relevant.add(new RelevantMatch(
                    candidate,
                    listing,
                    ListingDiscoveryFacetBuilder.matchQuality(request.query(), listing),
                    assessment,
                    fusedOrder));
            fusedOrder++;
        }
        boolean resultsFirst = ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION.equals(
                request.responseSchemaVersion()) || conceptRerank;
        if (conceptRerank) {
            relevant.sort(Comparator
                    .comparing((RelevantMatch item) -> item.assessment().relevance())
                    .thenComparing(item -> item.assessment().score(), Comparator.reverseOrder())
                    .thenComparingInt(RelevantMatch::fusedOrder));
        } else if (resultsFirst) {
            relevant.sort(Comparator
                    .comparing((RelevantMatch item) -> item.quality().exact()).reversed()
                    .thenComparing(
                            item -> item.quality().matchedTerms(), Comparator.reverseOrder())
                    .thenComparingInt(RelevantMatch::fusedOrder));
        }
        List<ListingHybridRrf.FusedCandidate> relevantCandidates = relevant.stream()
                .map(RelevantMatch::candidate).toList();
        List<ListingHybridSearchListing> relevantListings = relevant.stream()
                .map(RelevantMatch::listing).toList();
        List<ListingHybridSearchResponse.Result> results = new ArrayList<>();
        for (int index = 0; index < relevantListings.size() && index < request.limit(); index++) {
            ListingHybridRrf.FusedCandidate candidate = relevantCandidates.get(index);
            ListingHybridSearchListing listing = relevantListings.get(index);
            RelevantMatch match = relevant.get(index);
            ListingConceptCompatibilityReranker.Assessment assessment = match.assessment();
            results.add(new ListingHybridSearchResponse.Result(
                    listing.listingId(),
                    listing.listingVersion(),
                    listing.title(),
                    listing.categoryId(),
                    listing.categorySlug(),
                    listing.categoryName(),
                    listing.condition(),
                    listing.priceAmount(),
                    listing.currency(),
                    listing.publicCity(),
                    listing.publicRegion(),
                    true,
                    listing.primaryImageUrl(),
                    listing.publishedAt(),
                    INDIVIDUAL_TRANSACTION_NOTICE,
                    new ListingHybridSearchResponse.Provenance(
                            results.size() + 1,
                            candidate.mode(),
                            candidate.matchedBy(),
                            candidate.reasonCode()),
                    conceptRerank ? new ListingHybridSearchResponse.ConceptMatch(
                            assessment.relevance().name(),
                            assessment.completeConceptMatch(),
                            assessment.productTypeCompatible(),
                            assessment.matchedConcepts().stream().sorted().toList()) : null));
        }
        boolean facets = resultsFirst || ListingHybridSearchResponse.FACET_SCHEMA_VERSION.equals(
                request.responseSchemaVersion());
        String confidence = retrievalConfidence(
                totalMatches, relevantCandidates, retrieval.degraded());
        String reason = totalMatches == 0
                ? "CATEGORY_UNAVAILABLE"
                : confidence.equals("LOW")
                ? "LOW_RELEVANCE"
                : "RESULTS_AVAILABLE";
        ListingHybridSearchResponse.Discovery discovery = facets ? (conceptRerank
                ? conceptDiscovery(request, totalMatches, relevant, interpreted, confidence, reason)
                : resultsFirst
                ? ListingDiscoveryFacetBuilder.build(
                        request.query(), totalMatches, relevantListings, confidence, reason)
                : ListingDiscoveryFacetBuilder.buildV2(request.query(), relevantListings))
                : null;
        return new ListingHybridSearchResponse(
                request.responseSchemaVersion(),
                List.copyOf(results),
                new ListingHybridSearchResponse.Meta(
                        retrieval.mode(),
                        retrieval.degraded(),
                        clock.instant()),
                discovery);
    }

    // Publishes only bounded concept labels and counts; numeric ranking scores remain internal.
    private ListingHybridSearchResponse.Discovery conceptDiscovery(
            ListingHybridSearchRequest request,
            int totalMatches,
            List<RelevantMatch> relevant,
            ListingQueryConcept interpreted,
            String confidence,
            String reason) {
        List<ListingHybridSearchListing> listings = relevant.stream()
                .map(RelevantMatch::listing).toList();
        ListingHybridSearchResponse.Discovery base = ListingDiscoveryFacetBuilder.build(
                request.query(), totalMatches, listings, confidence, reason);
        int exact = (int) relevant.stream().filter(item -> item.assessment().relevance()
                == ListingConceptCompatibilityReranker.Relevance.HIGH).count();
        int related = relevant.size() - exact;
        return new ListingHybridSearchResponse.Discovery(
                base.normalizedCategory(), totalMatches, relevant.size(), exact, related,
                confidence, relevant.isEmpty() && totalMatches > 0 ? "LOW_RELEVANCE" : reason,
                base.facets(),
                new ListingHybridSearchResponse.RerankContext(
                        interpreted.coreConcepts().stream()
                                .map(ListingQueryConcept.ConceptGroup::label).toList(),
                        interpreted.candidateProductTypes(), interpreted.excludedBroadTypes()),
                Math.max(0, totalMatches - relevant.size()));
    }

    // Converts only Product-owned revalidated candidate evidence into a bounded label.
    private String retrievalConfidence(
            int totalMatches,
            List<ListingHybridRrf.FusedCandidate> relevantCandidates,
            boolean degraded) {
        if (totalMatches == 0 || relevantCandidates.isEmpty()) {
            return "LOW";
        }
        long lexical = relevantCandidates.stream()
                .filter(candidate -> candidate.lexicalRank() != null)
                .count();
        double relevantRatio = (double) relevantCandidates.size() / totalMatches;
        double lexicalRatio = (double) lexical / relevantCandidates.size();
        if (relevantRatio >= 0.75d && lexicalRatio >= 0.50d && !degraded) {
            return "HIGH";
        }
        if (relevantRatio >= 0.50d) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean matches(
            ListingHybridSearchRequest.Filters filters,
            ListingHybridSearchListing listing) {
        return listing.available()
                && equalsIfPresent(filters.categoryId(), listing.categoryId())
                && equalsIfPresent(filters.condition(), listing.condition())
                && equalsIfPresent(filters.currency(), listing.currency())
                && equalsIgnoreCaseIfPresent(filters.city(), listing.publicCity())
                && equalsIgnoreCaseIfPresent(filters.publicRegion(), listing.publicRegion())
                && (filters.minPrice() == null
                || listing.priceAmount().compareTo(filters.minPrice()) >= 0)
                && (filters.maxPrice() == null
                || listing.priceAmount().compareTo(filters.maxPrice()) <= 0);
    }

    private boolean equalsIfPresent(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private boolean equalsIgnoreCaseIfPresent(String expected, String actual) {
        return expected == null
                || (actual != null
                && expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT)));
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            metrics.record("request", "authentication_required");
            throw new ListingAuthorizationException("Agent service authentication is required.");
        }
    }

    @FunctionalInterface
    private interface BranchSearch {
        List<String> search();
    }

    @FunctionalInterface
    private interface StageOperation {
        void run();
    }

    private record RelevantMatch(
            ListingHybridRrf.FusedCandidate candidate,
            ListingHybridSearchListing listing,
            ListingDiscoveryFacetBuilder.MatchQuality quality,
            ListingConceptCompatibilityReranker.Assessment assessment,
            int fusedOrder) {
    }

    @FunctionalInterface
    private interface StageSupplier<T> {
        T get();
    }

    private record BranchResult(List<String> ids, boolean failed) {
    }

    private record Retrieval(
            List<String> lexicalIds,
            List<String> vectorIds,
            String mode,
            boolean degraded) {
    }
}
