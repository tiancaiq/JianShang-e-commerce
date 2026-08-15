package com.msb.ecom.product_service.search.hybrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ListingHybridRrf {

    static final int RRF_CONSTANT = 60;
    static final int MAX_BRANCH_CANDIDATES = 60;
    static final int MAX_FUSED_CANDIDATES = 80;

    private ListingHybridRrf() {
    }

    // Fuses only server-bounded branch ranks; raw branch scores never cross this boundary.
    public static List<FusedCandidate> fuse(
            List<String> lexicalIds,
            List<String> vectorIds) {
        Map<String, MutableCandidate> candidates = new LinkedHashMap<>();
        add(candidates, lexicalIds, true);
        add(candidates, vectorIds, false);
        return candidates.values().stream()
                .map(MutableCandidate::freeze)
                .sorted(Comparator
                        .comparingDouble(FusedCandidate::score).reversed()
                        .thenComparingInt(FusedCandidate::bestBranchRank)
                        .thenComparing(FusedCandidate::listingId))
                .limit(MAX_FUSED_CANDIDATES)
                .toList();
    }

    private static void add(
            Map<String, MutableCandidate> candidates,
            List<String> ids,
            boolean lexical) {
        if (ids == null) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (unique.size() == MAX_BRANCH_CANDIDATES) {
                break;
            }
            if (id == null || !unique.add(id)) {
                continue;
            }
            int rank = unique.size();
            MutableCandidate candidate =
                    candidates.computeIfAbsent(id, MutableCandidate::new);
            candidate.score += 1.0d / (RRF_CONSTANT + rank);
            if (lexical) {
                candidate.lexicalRank = rank;
            } else {
                candidate.vectorRank = rank;
            }
        }
    }

    public record FusedCandidate(
            String listingId,
            Integer lexicalRank,
            Integer vectorRank,
            double score
    ) {
        public int bestBranchRank() {
            int lexical = lexicalRank == null ? Integer.MAX_VALUE : lexicalRank;
            int vector = vectorRank == null ? Integer.MAX_VALUE : vectorRank;
            return Math.min(lexical, vector);
        }

        public List<String> matchedBy() {
            List<String> matches = new ArrayList<>(2);
            if (lexicalRank != null) {
                matches.add("LEXICAL");
            }
            if (vectorRank != null) {
                matches.add("VECTOR");
            }
            return List.copyOf(matches);
        }

        public String mode() {
            if (lexicalRank != null && vectorRank != null) {
                return "HYBRID";
            }
            return lexicalRank != null ? "LEXICAL_ONLY" : "VECTOR_ONLY";
        }

        public String reasonCode() {
            return switch (mode()) {
                case "HYBRID" -> "LEXICAL_AND_VECTOR_MATCH";
                case "LEXICAL_ONLY" -> "LEXICAL_MATCH";
                default -> "VECTOR_MATCH";
            };
        }
    }

    private static final class MutableCandidate {
        private final String listingId;
        private Integer lexicalRank;
        private Integer vectorRank;
        private double score;

        private MutableCandidate(String listingId) {
            this.listingId = listingId;
        }

        private FusedCandidate freeze() {
            return new FusedCandidate(listingId, lexicalRank, vectorRank, score);
        }
    }
}
