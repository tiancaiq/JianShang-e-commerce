package com.msb.ecom.product_service.search.hybrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listing.search.hybrid.concept-ranking")
public record ListingConceptRankingProperties(
        int exactPhraseBoost,
        int titleConceptWeight,
        int structuredTypeWeight,
        int descriptionConceptWeight,
        int missingConceptPenalty,
        int unrelatedTypePenalty,
        int minimumDirectScore,
        int minimumRelatedScore
) {
}
