package com.msb.ecom.product_service.search.hybrid;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ListingDiscoveryFacetBuilder {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> CHAIR_ACCESSORIES = Set.of(
            "cover", "covers", "cushion", "cushions", "wheel", "wheels",
            "caster", "casters", "mat", "mats", "slipcover", "armrest");
    private static final Set<String> QUERY_FILLER = Set.of(
            "a", "an", "and", "current", "find", "for", "give", "in", "listing",
            "listings", "marketplace", "me", "near", "of", "or", "please", "show",
            "the", "to", "under", "want", "with");

    private ListingDiscoveryFacetBuilder() {
    }

    // Builds only Product-derived, bounded facets from revalidated relevant listings.
    static ListingHybridSearchResponse.Discovery build(
            String query,
            int totalMatches,
            List<ListingHybridSearchListing> listings,
            String retrievalConfidence,
            String reason) {
        String normalized = normalize(query);
        Map<String, Integer> subtype = new LinkedHashMap<>();
        Map<String, Integer> condition = new LinkedHashMap<>();
        Map<String, Integer> price = new LinkedHashMap<>();
        Map<String, Integer> location = new LinkedHashMap<>();
        for (ListingHybridSearchListing listing : listings) {
            increment(subtype, subtype(normalized, listing));
            increment(condition, displayCondition(listing.condition()));
            increment(price, priceBand(listing.priceAmount(), listing.currency()));
            increment(location, location(listing));
        }
        int exactMatches = (int) listings.stream()
                .filter(listing -> matchQuality(query, listing).exact())
                .count();
        return new ListingHybridSearchResponse.Discovery(
                normalized,
                totalMatches,
                listings.size(),
                exactMatches,
                listings.size() - exactMatches,
                retrievalConfidence,
                reason,
                new ListingHybridSearchResponse.Facets(
                        values(subtype), values(condition), values(price), values(location)));
    }

    // Preserves the deployed V2 discovery projection for rolling Product/Agent updates.
    static ListingHybridSearchResponse.Discovery buildV2(
            String query,
            List<ListingHybridSearchListing> listings) {
        String reason = listings.isEmpty() ? "CATEGORY_UNAVAILABLE" : "RESULTS_AVAILABLE";
        ListingHybridSearchResponse.Discovery v3 = build(
                query, listings.size(), listings, "LOW", reason);
        return new ListingHybridSearchResponse.Discovery(
                v3.normalizedCategory(), v3.totalMatches(), reason, v3.facets());
    }

    // Gives Product-owned exact multi-concept matches priority without exposing scores.
    static MatchQuality matchQuality(String query, ListingHybridSearchListing listing) {
        Set<String> requested = new LinkedHashSet<>(tokens(normalize(query)));
        requested.removeAll(QUERY_FILLER);
        requested.removeIf(token -> token.isBlank() || token.chars().allMatch(Character::isDigit));
        Set<String> searchable = tokens(normalize(String.join(" ",
                listing.title(), listing.categoryName(), listing.categorySlug())));
        int matched = (int) requested.stream().filter(searchable::contains).count();
        return new MatchQuality(!requested.isEmpty() && matched == requested.size(), matched);
    }

    record MatchQuality(boolean exact, int matchedTerms) {
    }

    static boolean relevant(String query, ListingHybridSearchListing listing) {
        String normalizedQuery = normalize(query);
        String searchable = normalize(String.join(" ",
                listing.title(), listing.categoryName(), listing.categorySlug()));
        if (normalizedQuery.contains("chair")) {
            if (tokens(searchable).stream().anyMatch(CHAIR_ACCESSORIES::contains)) {
                return false;
            }
            boolean chair = searchable.contains("chair")
                    || searchable.contains("office seating")
                    || searchable.contains("task seating");
            String requestedSubtype = requestedChairSubtype(normalizedQuery);
            return chair && (requestedSubtype == null
                    || requestedSubtype.equals(chairSubtype(searchable)));
        }
        Set<String> alternatives = alternatives(normalizedQuery);
        return alternatives.stream().anyMatch(searchable::contains);
    }

    private static String subtype(String query, ListingHybridSearchListing listing) {
        String searchable = normalize(String.join(" ",
                listing.title(), listing.categoryName(), listing.categorySlug()));
        if (query.contains("chair")) {
            return chairSubtype(searchable);
        }
        return listing.categoryName();
    }

    private static String requestedChairSubtype(String query) {
        if (query.contains("gaming")) {
            return "Gaming Chair";
        }
        if (query.contains("dining")) {
            return "Dining Chair";
        }
        if (query.contains("folding")) {
            return "Folding Chair";
        }
        if (query.contains("office") || query.contains("desk chair")
                || query.contains("task chair") || query.contains("ergonomic")) {
            return "Office Chair";
        }
        return null;
    }

    private static String chairSubtype(String searchable) {
        if (searchable.contains("gaming")) {
            return "Gaming Chair";
        }
        if (searchable.contains("dining")) {
            return "Dining Chair";
        }
        if (searchable.contains("folding")) {
            return "Folding Chair";
        }
        if (searchable.contains("office") || searchable.contains("desk chair")
                || searchable.contains("task chair")
                || searchable.contains("office seating")
                || searchable.contains("task seating")
                || searchable.contains("ergonomic")) {
            return "Office Chair";
        }
        return "Chair";
    }

    private static Set<String> alternatives(String query) {
        if (query.contains("laptop")) {
            return Set.of("laptop", "notebook", "macbook", "chromebook");
        }
        if (query.contains("phone")) {
            return Set.of("phone", "smartphone", "iphone", "android", "pixel", "galaxy");
        }
        if (query.contains("bicycle") || query.contains("bike")) {
            return Set.of("bicycle", "bike", "cycle");
        }
        return tokens(query);
    }

    private static Set<String> tokens(String value) {
        return new LinkedHashSet<>(List.of(value.split(" ")));
    }

    private static String normalize(String value) {
        String canonical = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(canonical).replaceAll(" ").trim().replaceAll(" +", " ");
    }

    private static String displayCondition(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String priceBand(BigDecimal amount, String currency) {
        if (amount.compareTo(new BigDecimal("50")) < 0) {
            return "Under 50 " + currency;
        }
        if (amount.compareTo(new BigDecimal("100")) < 0) {
            return "50-99 " + currency;
        }
        if (amount.compareTo(new BigDecimal("250")) < 0) {
            return "100-249 " + currency;
        }
        return "250+ " + currency;
    }

    private static String location(ListingHybridSearchListing listing) {
        if (listing.publicCity() != null) {
            return listing.publicCity();
        }
        return listing.publicRegion();
    }

    private static void increment(Map<String, Integer> counts, String value) {
        if (value != null && !value.isBlank()) {
            counts.merge(value, 1, Integer::sum);
        }
    }

    private static List<ListingHybridSearchResponse.FacetValue> values(
            Map<String, Integer> counts) {
        List<ListingHybridSearchResponse.FacetValue> result = new ArrayList<>();
        counts.forEach((value, count) -> result.add(
                new ListingHybridSearchResponse.FacetValue(value, count)));
        result.sort(Comparator
                .comparingInt(ListingHybridSearchResponse.FacetValue::count).reversed()
                .thenComparing(ListingHybridSearchResponse.FacetValue::value));
        return List.copyOf(result.subList(0, Math.min(result.size(), 12)));
    }
}
