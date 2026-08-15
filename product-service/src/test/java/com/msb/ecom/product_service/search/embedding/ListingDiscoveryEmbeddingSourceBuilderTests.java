package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingDiscoveryEmbeddingSourceBuilderTests {

    private final ListingDiscoveryEmbeddingSourceBuilder builder =
            new ListingDiscoveryEmbeddingSourceBuilder(new ObjectMapper());

    @Test
    void canonicalTextUsesFixedOrderNormalizationAndDeterministicHashes() {
        PublicListingResponse listing = listing(
                "  Cafe\u0301\r\n desk\t chair  ",
                "  Ergonomic\u00a0 seating \r for work  ",
                "Office\tChairs",
                "office-chairs");

        ListingDiscoveryEmbeddingSource first = builder.build(listing, 7, images());
        ListingDiscoveryEmbeddingSource second = builder.build(listing, 7, images());

        assertThat(first.embeddingText()).isEqualTo("""
                TITLE
                Café desk chair
                CATEGORY
                Office Chairs
                office-chairs
                DESCRIPTION
                Ergonomic seating for work""");
        assertThat(first.documentHash()).isEqualTo(second.documentHash()).hasSize(64);
        assertThat(first.embeddingInputHash()).isEqualTo(second.embeddingInputHash()).hasSize(64);
        assertThat(first.language()).isEqualTo("und");
        assertThat(first.embeddingText()).hasSizeLessThanOrEqualTo(8_000);
    }

    @Test
    void redactsContactsUrlsControlsAndBidiOverridesBeforeBoundary() {
        PublicListingResponse listing = listing(
                "Desk\u0000 chair",
                "Email seller@example.com or call +1 (949) 555-1212. "
                        + "Visit https://private.example/path?q=secret \u202Ehidden",
                "Furniture",
                "furniture");

        ListingDiscoveryEmbeddingSource source = builder.build(listing, 7, images());

        assertThat(source.embeddingText())
                .contains("[redacted-email]", "[redacted-phone]", "[redacted-url]")
                .doesNotContain(
                        "seller@example.com",
                        "949",
                        "private.example",
                        "\u0000",
                        "\u202E");
    }

    @Test
    void embeddingTextExcludesDynamicAndPrivateDiscoveryFacts() {
        ListingDiscoveryEmbeddingSource source = builder.build(
                listing("Public title", "Public description", "Furniture", "furniture"),
                7,
                images());

        assertThat(source.embeddingText())
                .doesNotContain(
                        "120.00",
                        "USD",
                        "GOOD",
                        "Irvine",
                        "Orange County",
                        "INDIVIDUAL",
                        "image-one",
                        "transaction",
                        "seller");
    }

    @Test
    void authoritativeFieldLimitsAreEnforcedWithoutTruncation() {
        assertThatThrownBy(() -> builder.build(
                listing("x".repeat(161), "description", "Furniture", "furniture"),
                7,
                images()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(
                listing("title", "x".repeat(5_001), "Furniture", "furniture"),
                7,
                images()))
                .isInstanceOf(IllegalArgumentException.class);

        ListingDiscoveryEmbeddingSource maximum = builder.build(
                listing("x".repeat(160), "y".repeat(5_000), "z".repeat(160), "s".repeat(120)),
                7,
                images());
        assertThat(maximum.embeddingText()).hasSizeLessThanOrEqualTo(8_000);
    }

    private PublicListingResponse listing(
            String title,
            String description,
            String categoryName,
            String categorySlug) {
        return new PublicListingResponse(
                "01ARZ3NDEKTSV4RRFFQ69G5FAC",
                "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                "Private seller label",
                null,
                null,
                null,
                null,
                false,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB",
                categorySlug,
                categoryName,
                title,
                description,
                "GOOD",
                "Private condition notes",
                new BigDecimal("120.00"),
                "USD",
                true,
                1,
                "Irvine",
                "Orange County",
                Instant.parse("2026-07-23T10:00:00Z"),
                "Public transaction notice",
                100,
                50,
                images());
    }

    private List<PublicListingImageResponse> images() {
        return List.of(new PublicListingImageResponse(
                "01ARZ3NDEKTSV4RRFFQ69G5FAD",
                0,
                "Public image",
                "public.png",
                "image/png",
                100,
                null,
                "/api/v1/listings/images/image-one/content"));
    }
}
