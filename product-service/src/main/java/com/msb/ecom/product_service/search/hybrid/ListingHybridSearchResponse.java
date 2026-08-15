package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingHybridSearchResponse(
        String schemaVersion,
        List<Result> data,
        Meta meta,
        Discovery discovery
) {

    public static final String SCHEMA_VERSION = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1";
    public static final String FACET_SCHEMA_VERSION = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2";
    public static final String RESULTS_FIRST_SCHEMA_VERSION =
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3";
    public static final String CONCEPT_RERANK_SCHEMA_VERSION =
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4";

    public ListingHybridSearchResponse(String schemaVersion, List<Result> data, Meta meta) {
        this(schemaVersion, data, meta, null);
    }

    public record Result(
            String listingId,
            long listingVersion,
            String title,
            String categoryId,
            String categorySlug,
            String categoryName,
            String condition,
            BigDecimal priceAmount,
            String currency,
            String publicCity,
            String publicRegion,
            boolean available,
            String primaryImageUrl,
            Instant publishedAt,
            String transactionNotice,
            Provenance provenance,
            ConceptMatch conceptMatch
    ) {
        public Result(
                String listingId,
                long listingVersion,
                String title,
                String categoryId,
                String categorySlug,
                String categoryName,
                String condition,
                BigDecimal priceAmount,
                String currency,
                String publicCity,
                String publicRegion,
                boolean available,
                String primaryImageUrl,
                Instant publishedAt,
                String transactionNotice,
                Provenance provenance) {
            this(listingId, listingVersion, title, categoryId, categorySlug, categoryName,
                    condition, priceAmount, currency, publicCity, publicRegion, available,
                    primaryImageUrl, publishedAt, transactionNotice, provenance, null);
        }
    }

    public record ConceptMatch(
            String relevance,
            boolean completeConceptMatch,
            boolean productTypeCompatible,
            List<String> matchedConcepts
    ) {
    }

    public record Provenance(
            int finalRank,
            String mode,
            List<String> matchedBy,
            String reasonCode
    ) {
    }

    public record Meta(
            String retrievalMode,
            boolean degraded,
            Instant checkedAt
    ) {
    }

    public record Discovery(
            String normalizedCategory,
            int totalMatches,
            Integer relevantMatchCount,
            Integer exactMatchCount,
            Integer relatedMatchCount,
            String retrievalConfidence,
            String reason,
            Facets facets,
            RerankContext rerankContext,
            Integer rejectedCandidateCount
    ) {
        public Discovery(
                String normalizedCategory,
                int totalMatches,
                Integer relevantMatchCount,
                Integer exactMatchCount,
                Integer relatedMatchCount,
                String retrievalConfidence,
                String reason,
                Facets facets) {
            this(normalizedCategory, totalMatches, relevantMatchCount, exactMatchCount,
                    relatedMatchCount, retrievalConfidence, reason, facets, null, null);
        }

        public Discovery(
                String normalizedCategory,
                int totalMatches,
                String reason,
                Facets facets) {
            this(normalizedCategory, totalMatches, null, null, null, null, reason, facets,
                    null, null);
        }
    }

    public record RerankContext(
            List<String> coreConcepts,
            List<String> candidateProductTypes,
            List<String> excludedBroadTypes
    ) {
    }

    public record Facets(
            List<FacetValue> subtype,
            List<FacetValue> condition,
            List<FacetValue> priceBand,
            List<FacetValue> location
    ) {
    }

    public record FacetValue(String value, int count) {
    }
}
