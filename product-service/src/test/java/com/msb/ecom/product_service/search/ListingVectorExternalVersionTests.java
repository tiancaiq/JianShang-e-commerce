package com.msb.ecom.product_service.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingVectorExternalVersionTests {

    @Test
    void mapsZeroBasedListingVersionsToStrictlyOrderedPositiveVersions() {
        assertThat(ListingVectorExternalVersion.lexical(0)).isEqualTo(1);
        assertThat(ListingVectorExternalVersion.vector(0)).isEqualTo(2);
        assertThat(ListingVectorExternalVersion.lexical(1)).isEqualTo(3);
        assertThat(ListingVectorExternalVersion.vector(1)).isEqualTo(4);
        assertThat(ListingVectorExternalVersion.lexical(7)).isEqualTo(15);
        assertThat(ListingVectorExternalVersion.vector(7)).isEqualTo(16);
    }

    @Test
    void currentVectorAlwaysPrecedesTheNextListingVersionsLexicalTruth() {
        for (long version : new long[]{0L, 1L, 7L, 1_000_000L}) {
            assertThat(ListingVectorExternalVersion.lexical(version))
                    .isLessThan(ListingVectorExternalVersion.vector(version));
            assertThat(ListingVectorExternalVersion.vector(version))
                    .isLessThan(ListingVectorExternalVersion.lexical(version + 1L));
        }
    }

    @Test
    void acceptsTheDerivedMaximumAndRejectsNegativeOrOverflowingVersions() {
        long maximum = ListingVectorExternalVersion.MAX_LISTING_VERSION;
        assertThat(maximum).isEqualTo((Long.MAX_VALUE - 2L) / 2L);
        assertThat(ListingVectorExternalVersion.lexical(maximum))
                .isEqualTo(Long.MAX_VALUE - 2L);
        assertThat(ListingVectorExternalVersion.vector(maximum))
                .isEqualTo(Long.MAX_VALUE - 1L);

        assertThatThrownBy(() -> ListingVectorExternalVersion.vector(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingVectorExternalVersion.lexical(maximum + 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingVectorExternalVersion.vector(maximum + 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
