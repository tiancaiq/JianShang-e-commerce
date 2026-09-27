package com.msb.ecom.product_service.search.hybrid;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
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

    @ParameterizedTest
    @CsvSource({
            "Walnut desktop radio with warm dial light,Radio sized for a writing desk",
            "Bundle of Urban Nori puzzle storage case,A case that can sit beside a desk",
            "Walnut Brown folding picnic mat by Hearthlane,A mat stored near a desk",
            "Harbor Business Desk Lamp,LED lighting for a desk",
            "Little Metro canvas tote bag for desk use,Canvas bag",
            "Amber Loop noise isolating earbuds for desk use,Wireless earbuds",
            "Amber Loop bedside reading lamp for desk use,Reading lamp",
            "Luna language workbook set for desk use,Workbook set"
    })
    void deskSearchRejectsDescriptionMentionsAndDeskAccessories(
            String unrelatedTitle,
            String unrelatedDescription) {
        var interpreted = ListingQueryConcept.interpret("desk");

        var desk = reranker.assess(
                interpreted,
                listing("Restored oak writing desk", "Solid wood writing desk"));
        var unrelated = reranker.assess(
                interpreted,
                listing(unrelatedTitle, unrelatedDescription));

        assertThat(desk.relevance())
                .isEqualTo(ListingConceptCompatibilityReranker.Relevance.HIGH);
        assertThat(desk.productTypeCompatible()).isTrue();
        assertThat(unrelated.relevance())
                .isEqualTo(ListingConceptCompatibilityReranker.Relevance.LOW);
        assertThat(unrelated.productTypeCompatible()).isFalse();
    }

    @Test
    void exactDeskAccessoryQueryRemainsCompatibleWithItsDisplayedProductType() {
        var interpreted = ListingQueryConcept.interpret("Harbor Business Desk Lamp");

        var lamp = reranker.assess(
                interpreted,
                listing("Harbor Business Desk Lamp", "LED lighting for a desk"));

        assertThat(lamp.relevance())
                .isEqualTo(ListingConceptCompatibilityReranker.Relevance.HIGH);
        assertThat(lamp.productTypeCompatible()).isTrue();
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
