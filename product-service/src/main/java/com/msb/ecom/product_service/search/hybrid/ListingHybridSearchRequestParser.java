package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.core.id.Ulid;
import com.msb.ecom.product_service.model.ListingCondition;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingVectorCodec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ListingHybridSearchRequestParser {

    public static final int MAX_BODY_BYTES = 65_536;
    public static final String REQUEST_SCHEMA_VERSION = "MARKETPLACE_HYBRID_SEARCH_V1";

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "query", "embeddingIdentity", "embedding",
            "filters", "sort", "limit", "responseSchemaVersion");
    private static final Set<String> REQUIRED_ROOT_FIELDS = Set.of(
            "schemaVersion", "query", "embeddingIdentity", "embedding");
    private static final Set<String> IDENTITY_FIELDS =
            Set.of("provider", "model", "dimensions");
    private static final Set<String> FILTER_FIELDS = Set.of(
            "sellerType", "categoryId", "condition", "minPrice", "maxPrice",
            "currency", "city", "county", "availability");
    private static final Set<Integer> BIDI = Set.of(
            0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D,
            0x202E, 0x2066, 0x2067, 0x2068, 0x2069);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999999999.99");

    private final ObjectMapper mapper;

    public ListingHybridSearchRequestParser(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // Parses a bounded strict request and turns the embedding into finite canonical float32 values.
    public ParsedRequest parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw invalid();
        }
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            requireObject(root, ROOT_FIELDS, REQUIRED_ROOT_FIELDS);
            JsonNode identity = root.get("embeddingIdentity");
            requireObject(identity, IDENTITY_FIELDS, IDENTITY_FIELDS);
            JsonNode filters = root.get("filters");
            if (filters != null && !filters.isNull()) {
                requireObject(filters, FILTER_FIELDS, Set.of());
            }
            String query = query(root.get("query"));
            var canonical = ListingDiscoveryEmbeddingVectorCodec.encode(root.get("embedding"));
            float[] embedding = ListingDiscoveryEmbeddingVectorCodec
                    .decode(canonical.bytes())
                    .values();
            ListingHybridSearchRequest.Filters parsedFilters = filters(filters);
            int limit = optionalInt(root.get("limit"), 20, 1, 20);
            exactOptional(root.get("sort"), "RELEVANCE");
            return new ParsedRequest(
                    new ListingHybridSearchRequest(
                            query, embedding, parsedFilters, limit,
                            responseSchemaVersion(root.get("responseSchemaVersion"))),
                    text(identity, "provider", 80),
                    text(identity, "model", 80),
                    integral(identity, "dimensions", 1, 16_000));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private ListingHybridSearchRequest.Filters filters(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return new ListingHybridSearchRequest.Filters(
                    null, null, null, null, null, null, null);
        }
        exactOptional(filters.get("sellerType"), "INDIVIDUAL");
        exactOptional(filters.get("availability"), "AVAILABLE");
        String categoryId = optionalText(filters.get("categoryId"), 26);
        if (categoryId != null) {
            categoryId = Ulid.parse(categoryId).value();
        }
        String condition = optionalText(filters.get("condition"), 30);
        if (condition != null) {
            condition = ListingCondition.valueOf(condition.toUpperCase(Locale.ROOT)).name();
        }
        BigDecimal min = price(filters.get("minPrice"));
        BigDecimal max = price(filters.get("maxPrice"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid();
        }
        String currency = optionalText(filters.get("currency"), 3);
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw invalid();
        }
        if ((min != null || max != null) && currency == null) {
            throw invalid();
        }
        return new ListingHybridSearchRequest.Filters(
                categoryId,
                condition,
                min,
                max,
                currency,
                optionalText(filters.get("city"), 100),
                optionalText(filters.get("county"), 100));
    }

    private String query(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        String normalized = normalize(value.textValue());
        if (normalized.isBlank() || normalized.length() > 200) {
            throw invalid();
        }
        return normalized;
    }

    private String normalize(String value) {
        String canonical = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder(canonical.length());
        canonical.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                safe.append(' ');
            } else if (!BIDI.contains(codePoint)
                    && Character.getType(codePoint) != Character.CONTROL) {
                safe.appendCodePoint(codePoint);
            }
        });
        return WHITESPACE.matcher(safe.toString()).replaceAll(" ").trim();
    }

    private BigDecimal price(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid();
        }
        BigDecimal price = value.decimalValue().stripTrailingZeros();
        if (price.signum() < 0
                || price.compareTo(MAX_PRICE) > 0
                || Math.max(0, price.scale()) > 2) {
            throw invalid();
        }
        return price.setScale(Math.max(0, price.scale()), RoundingMode.UNNECESSARY);
    }

    private void requireObject(
            JsonNode value,
            Set<String> allowed,
            Set<String> required) {
        if (value == null || !value.isObject()) {
            throw invalid();
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual) || !actual.containsAll(required)) {
            throw invalid();
        }
    }

    private String optionalText(JsonNode value, int maximum) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        String normalized = normalize(value.textValue());
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw invalid();
        }
        return normalized;
    }

    private String text(JsonNode parent, String field, int maximum) {
        String value = optionalText(parent.get(field), maximum);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private void exactOptional(JsonNode value, String expected) {
        if (value != null && !value.isNull()
                && (!value.isTextual() || !expected.equals(value.textValue()))) {
            throw invalid();
        }
    }

    private String responseSchemaVersion(JsonNode value) {
        if (value == null || value.isNull()) {
            return ListingHybridSearchResponse.SCHEMA_VERSION;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        String version = value.textValue();
      if (!ListingHybridSearchResponse.SCHEMA_VERSION.equals(version)
              && !ListingHybridSearchResponse.FACET_SCHEMA_VERSION.equals(version)
              && !ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION.equals(version)
              && !ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION.equals(version)) {
            throw invalid();
        }
        return version;
    }

    private int optionalInt(JsonNode value, int fallback, int minimum, int maximum) {
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        int parsed = value.intValue();
        if (parsed < minimum || parsed > maximum) {
            throw invalid();
        }
        return parsed;
    }

    private int integral(
            JsonNode parent,
            String field,
            int minimum,
            int maximum) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw invalid();
        }
        return optionalInt(value, -1, minimum, maximum);
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("The marketplace hybrid search request is invalid.");
    }

    public record ParsedRequest(
            ListingHybridSearchRequest request,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDimensions
    ) {
        public boolean hasApprovedIdentity() {
            return ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(embeddingProvider)
                    && ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(embeddingModel)
                    && ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS == embeddingDimensions;
        }
    }
}
