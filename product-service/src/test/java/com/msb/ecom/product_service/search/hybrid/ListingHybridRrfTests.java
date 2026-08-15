package com.msb.ecom.product_service.search.hybrid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListingHybridRrfTests {

    @Test
    void fusesWithExactUnweightedFormulaAndStableTieBreakers() {
        var result = ListingHybridRrf.fuse(
                List.of("01D00000000000000000000101", "01D00000000000000000000102"),
                List.of("01D00000000000000000000102", "01D00000000000000000000103"));

        assertThat(result).extracting(ListingHybridRrf.FusedCandidate::listingId)
                .containsExactly(
                        "01D00000000000000000000102",
                        "01D00000000000000000000101",
                        "01D00000000000000000000103");
        assertThat(result.getFirst().score())
                .isEqualTo(1.0d / 62.0d + 1.0d / 61.0d);
        assertThat(result.getFirst().matchedBy()).containsExactly("LEXICAL", "VECTOR");
        assertThat(result.getFirst().reasonCode()).isEqualTo("LEXICAL_AND_VECTOR_MATCH");
    }

    @Test
    void deduplicatesEachBranchAndBoundsInputsAndOutput() {
        List<String> lexical = new ArrayList<>();
        List<String> vector = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String id = String.format("01D0000000000000000000%04d", index);
            lexical.add(id);
            lexical.add(id);
            vector.add(String.format("01D0000000000000000001%04d", index));
        }

        var result = ListingHybridRrf.fuse(lexical, vector);

        assertThat(result).hasSize(ListingHybridRrf.MAX_FUSED_CANDIDATES);
        assertThat(result).extracting(ListingHybridRrf.FusedCandidate::listingId)
                .doesNotHaveDuplicates();
        assertThat(result).allMatch(candidate ->
                candidate.lexicalRank() == null
                        || candidate.lexicalRank() <= ListingHybridRrf.MAX_BRANCH_CANDIDATES);
    }
}
