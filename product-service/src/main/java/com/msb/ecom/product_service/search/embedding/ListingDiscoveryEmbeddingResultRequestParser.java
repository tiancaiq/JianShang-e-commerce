package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ListingDiscoveryEmbeddingResultRequestParser {

    public static final int MAX_BODY_BYTES = 65_536;
    public static final String RESULT_SCHEMA_VERSION =
            "MARKETPLACE_LISTING_EMBEDDING_RESULT_V1";

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion",
            "listingVersion",
            "documentSchemaVersion",
            "documentHash",
            "embeddingInputSchemaVersion",
            "embeddingInputHash",
            "embeddingIdentity",
            "vector");
    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "provider",
            "model",
            "dimensions");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IDENTITY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}");

    private final ObjectMapper strictObjectMapper;

    public ListingDiscoveryEmbeddingResultRequestParser(ObjectMapper objectMapper) {
        this.strictObjectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // Parses the bounded Agent callback without allowing Jackson scalar coercion or unknown fields.
    public ListingDiscoveryEmbeddingResultRequest parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw invalid();
        }
        try {
            JsonNode root = strictObjectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            requireObject(root, ROOT_FIELDS);
            JsonNode identity = root.get("embeddingIdentity");
            requireObject(identity, IDENTITY_FIELDS);
            return new ListingDiscoveryEmbeddingResultRequest(
                    exactText(root, "schemaVersion", RESULT_SCHEMA_VERSION),
                    nonnegativeLong(root, "listingVersion"),
                    exactText(
                            root,
                            "documentSchemaVersion",
                            ListingDiscoveryEmbeddingSourceBuilder.DOCUMENT_SCHEMA_VERSION),
                    hash(root, "documentHash"),
                    exactText(
                            root,
                            "embeddingInputSchemaVersion",
                            ListingDiscoveryEmbeddingSourceBuilder.INPUT_SCHEMA_VERSION),
                    hash(root, "embeddingInputHash"),
                    new ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity(
                            identity(identity, "provider"),
                            identity(identity, "model"),
                            positiveInt(identity, "dimensions", 16_000)),
                    ListingDiscoveryEmbeddingVectorCodec.encode(root.get("vector")));
        } catch (JsonProcessingException exception) {
            throw invalid();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private void requireObject(JsonNode value, Set<String> expectedFields) {
        if (value == null || !value.isObject()) {
            throw invalid();
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expectedFields)) {
            throw invalid();
        }
    }

    private String exactText(JsonNode parent, String field, String expected) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
            throw invalid();
        }
        return expected;
    }

    private String hash(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null
                || !value.isTextual()
                || !HASH.matcher(value.textValue()).matches()) {
            throw invalid();
        }
        return value.textValue();
    }

    private String identity(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null
                || !value.isTextual()
                || !IDENTITY.matcher(value.textValue()).matches()) {
            throw invalid();
        }
        return value.textValue();
    }

    private long nonnegativeLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        long parsed = value.longValue();
        if (parsed < 0) {
            throw invalid();
        }
        return parsed;
    }

    private int positiveInt(JsonNode parent, String field, int maximum) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        int parsed = value.intValue();
        if (parsed < 1 || parsed > maximum) {
            throw invalid();
        }
        return parsed;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "The listing discovery embedding result is invalid.");
    }
}
