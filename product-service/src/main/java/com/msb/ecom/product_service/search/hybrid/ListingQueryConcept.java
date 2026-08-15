package com.msb.ecom.product_service.search.hybrid;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

record ListingQueryConcept(
        String originalQuery,
        String normalizedQuery,
        List<ConceptGroup> coreConcepts,
        List<String> candidateProductTypes,
        List<String> excludedBroadTypes
) {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> FILLER = Set.of(
            "a", "an", "and", "for", "give", "in", "me", "near", "of", "or",
            "please", "show", "the", "to", "under", "with");

    // Produces a bounded Product-owned concept model without replacing the original query.
    static ListingQueryConcept interpret(String query) {
        String normalized = normalize(query);
        Set<String> tokens = tokens(normalized);
        if (tokens.contains("key") || tokens.contains("keys")) {
            if (intersects(tokens, Set.of("bag", "pouch", "case", "wallet", "organizer"))) {
                return new ListingQueryConcept(
                        query,
                        normalized,
                        List.of(
                                new ConceptGroup("key storage", Set.of("key", "keys", "keychain")),
                                new ConceptGroup(
                                        "pouch or case",
                                        Set.of("bag", "pouch", "case", "wallet", "organizer", "holder"))
                        ),
                        List.of("key pouch", "key case", "key wallet", "key organizer", "key bag"),
                        List.of("tote bag", "crossbody bag", "sling bag"));
            }
        }
        if (intersects(tokens, Set.of("laptop", "notebook", "macbook", "chromebook"))
                && intersects(tokens, Set.of("bag", "case", "sleeve", "backpack"))) {
            return new ListingQueryConcept(
                    query,
                    normalized,
                    List.of(
                            new ConceptGroup(
                                    "laptop computer",
                                    Set.of("laptop", "notebook", "macbook", "chromebook")),
                            new ConceptGroup(
                                    "carrying case",
                                    Set.of("bag", "case", "sleeve", "backpack"))
                    ),
                    List.of("laptop bag", "laptop case", "laptop sleeve", "notebook bag"),
                    List.of("handbag", "tote bag"));
        }
        if (intersects(tokens, Set.of("phone", "iphone", "android", "pixel", "galaxy"))
                && intersects(tokens, Set.of("case", "cover"))) {
            return new ListingQueryConcept(
                    query,
                    normalized,
                    List.of(
                            new ConceptGroup(
                                    "mobile phone",
                                    Set.of("phone", "iphone", "android", "pixel", "galaxy")),
                            new ConceptGroup("protective case", Set.of("case", "cover"))
                    ),
                    List.of("phone case", "phone cover", "iphone case", "pixel case"),
                    List.of("tablet case", "laptop case"));
        }
        if (tokens.contains("chair")) {
            if (intersects(tokens, Set.of("office", "desk", "task", "ergonomic"))) {
                return new ListingQueryConcept(
                        query,
                        normalized,
                        List.of(
                                new ConceptGroup(
                                        "office use",
                                        Set.of("office", "desk", "task", "ergonomic")),
                                new ConceptGroup("chair seating", Set.of("chair", "seating"))
                        ),
                        List.of(
                                "office chair", "desk chair", "task chair",
                                "office seating", "task seating"),
                        List.of("dining chair", "gaming chair", "folding chair",
                                "chair wheels", "chair cover", "chair cushion"));
            }
            return new ListingQueryConcept(
                    query,
                    normalized,
                    List.of(new ConceptGroup("chair seating", Set.of("chair", "seating"))),
                    List.of("chair", "seating"),
                    List.of("chair wheels", "chair cover", "chair cushion", "chair mat",
                            "chair caster", "chair armrest"));
        }
        if (intersects(tokens, Set.of("laptop", "notebook", "macbook", "chromebook"))) {
            return new ListingQueryConcept(
                    query,
                    normalized,
                    List.of(new ConceptGroup(
                            "laptop computer",
                            Set.of("laptop", "notebook", "macbook", "chromebook"))),
                    List.of("laptop", "notebook", "macbook", "chromebook"),
                    List.of("laptop bag", "laptop case", "laptop sleeve", "laptop stand",
                            "laptop charger", "laptop dock"));
        }
        if (tokens.contains("desk") && tokens.contains("organizer")) {
            return new ListingQueryConcept(
                    query,
                    normalized,
                    List.of(
                            new ConceptGroup("desk use", Set.of("desk", "desktop", "office")),
                            new ConceptGroup(
                                    "organizer storage",
                                    Set.of("organizer", "caddy", "storage"))
                    ),
                    List.of("desk organizer", "desktop organizer", "desk caddy"),
                    List.of("closet organizer", "key organizer"));
        }
        List<ConceptGroup> groups = new ArrayList<>();
        for (String token : tokens) {
            if (!FILLER.contains(token) && !token.chars().allMatch(Character::isDigit)) {
                groups.add(new ConceptGroup(token, Set.of(token)));
            }
        }
        return new ListingQueryConcept(
                query,
                normalized,
                List.copyOf(groups),
                List.of(normalized),
                List.of());
    }

    List<String> lexicalCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedQuery);
        candidates.addAll(candidateProductTypes);
        return List.copyOf(candidates);
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    static Set<String> tokens(String value) {
        return new LinkedHashSet<>(List.of(normalize(value).split(" ")));
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String canonical = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(canonical).replaceAll(" ").trim().replaceAll(" +", " ");
    }

    record ConceptGroup(String label, Set<String> aliases) {
    }
}
