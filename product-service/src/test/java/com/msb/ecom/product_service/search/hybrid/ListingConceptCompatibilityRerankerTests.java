package com.msb.ecom.product_service.search.hybrid;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ListingConceptCompatibilityRerankerTests {

    private final ListingConceptCompatibilityReranker reranker =
            new ListingConceptCompatibilityReranker(
                    new ListingConceptRankingProperties(12, 8, 5, 2, 12, 20, 12, 2));

    @ParameterizedTest
    @CsvSource({
            "key bag,Leather key pouch,Compact case for house keys,Canvas tote bag",
            "key organizer,Wall key organizer,Hooks for keys,Desk organizer",
            "key pouch,Zip key pouch,Small holder for keys,Coin pouch",
            "office chair,Task seating ergonomic,Office workstation chair,Dining chair",
            "chair,Folding chair,Portable seating,Chair wheels",
            "laptop bag,Notebook sleeve,Protective laptop carrying case,Camera bag",
            "laptop,Student notebook,Reliable laptop computer,Laptop stand",
            "phone case,Pixel phone cover,Protective smartphone case,Tablet case",
            "key case,Hard key case,Protective holder for keys,Pencil case",
            "desk organizer,Desktop caddy,Office desk storage,Closet organizer"
    })
    void tenQuerySetRequiresCoreConceptsAndRejectsIncompatiblePartialMatches(
            String query,
            String strongTitle,
            String strongDescription,
            String irrelevantTitle) {
        var interpreted = ListingQueryConcept.interpret(query);

        var strong = reranker.assess(
                interpreted, listing(strongTitle, strongDescription));
        var irrelevant = reranker.assess(
                interpreted, listing(irrelevantTitle, "General marketplace item"));

        assertThat(strong.relevance())
                .isEqualTo(ListingConceptCompatibilityReranker.Relevance.HIGH);
        assertThat(strong.completeConceptMatch()).isTrue();
        assertThat(strong.productTypeCompatible()).isTrue();
        assertThat(irrelevant.relevance())
                .isEqualTo(ListingConceptCompatibilityReranker.Relevance.LOW);
    }

    private ListingHybridSearchListing listing(String title, String description) {
        return new ListingHybridSearchListing(
                "01D00000000000000000000101",
                1,
                "01D00000000000000000000201",
                "general",
                "General",
                title,
                "GOOD",
                new BigDecimal("20.00"),
                "USD",
                "Irvine",
                "Orange County",
                true,
                null,
                Instant.parse("2026-07-30T00:00:00Z"),
                description);
    }
}
