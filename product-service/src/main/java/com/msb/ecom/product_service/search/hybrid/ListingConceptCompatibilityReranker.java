package com.msb.ecom.product_service.search.hybrid;

import java.util.LinkedHashSet;
import java.util.Set;

final class ListingConceptCompatibilityReranker {

    private final ListingConceptRankingProperties properties;

    ListingConceptCompatibilityReranker(ListingConceptRankingProperties properties) {
        this.properties = properties;
    }

    // Scores only revalidated public listing fields and never creates listing attributes.
    Assessment assess(ListingQueryConcept query, ListingHybridSearchListing listing) {
        String title = ListingQueryConcept.normalize(listing.title());
        String structured = ListingQueryConcept.normalize(String.join(" ",
                listing.categoryName(), listing.categorySlug()));
        String description = ListingQueryConcept.normalize(listing.description());
        Set<String> titleTokens = ListingQueryConcept.tokens(title);
        Set<String> structuredTokens = ListingQueryConcept.tokens(structured);
        Set<String> descriptionTokens = ListingQueryConcept.tokens(description);
        Set<String> matched = new LinkedHashSet<>();
        int titleMatches = 0;
        int structuredMatches = 0;
        int descriptionMatches = 0;
        for (ListingQueryConcept.ConceptGroup group : query.coreConcepts()) {
            if (group.aliases().stream().anyMatch(titleTokens::contains)) {
                titleMatches++;
                matched.add(group.label());
            } else if (group.aliases().stream().anyMatch(structuredTokens::contains)) {
                structuredMatches++;
                matched.add(group.label());
            } else if (group.aliases().stream().anyMatch(descriptionTokens::contains)) {
                descriptionMatches++;
                matched.add(group.label());
            }
        }
        boolean complete = !query.coreConcepts().isEmpty()
                && matched.size() == query.coreConcepts().size();
        boolean productPhrase = query.candidateProductTypes().stream()
                .anyMatch(title::contains);
        boolean originalPhrase = title.contains(query.normalizedQuery());
        boolean broadType = query.excludedBroadTypes().stream().anyMatch(title::contains);
        boolean titleTypeCompatible = !deskFurnitureQuery(query)
                || deskFurnitureTitle(title);
        boolean productTypeCompatible = complete
                && (titleMatches > 0 || structuredMatches > 0)
                && !broadType
                && titleTypeCompatible;
        int missing = query.coreConcepts().size() - matched.size();
        int score = (originalPhrase || productPhrase ? properties.exactPhraseBoost() : 0)
                + titleMatches * properties.titleConceptWeight()
                + structuredMatches * properties.structuredTypeWeight()
                + descriptionMatches * properties.descriptionConceptWeight()
                - missing * properties.missingConceptPenalty()
                - (broadType ? properties.unrelatedTypePenalty() : 0);
        Relevance relevance;
        if (productTypeCompatible && score >= properties.minimumDirectScore()) {
            relevance = Relevance.HIGH;
        } else if (productTypeCompatible && score >= properties.minimumRelatedScore()) {
            relevance = Relevance.MEDIUM;
        } else {
            relevance = Relevance.LOW;
        }
        return new Assessment(
                relevance,
                complete,
                productTypeCompatible,
                Set.copyOf(matched),
                score);
    }

    private boolean deskFurnitureQuery(ListingQueryConcept query) {
        return query.coreConcepts().stream()
                .anyMatch(group -> "desk furniture".equals(group.label()));
    }

    private boolean deskFurnitureTitle(String title) {
        String productTitle = title
                .replaceFirst("\\s+(?:private sale|for sale)$", "")
                .trim();
        return productTitle.equals("desk")
                || productTitle.endsWith(" desk")
                || productTitle.contains(" desk with ")
                || productTitle.contains(" desk and ")
                || productTitle.startsWith("desk ");
    }

    enum Relevance { HIGH, MEDIUM, LOW }

    record Assessment(
            Relevance relevance,
            boolean completeConceptMatch,
            boolean productTypeCompatible,
            Set<String> matchedConcepts,
            int score
    ) {
    }
}
