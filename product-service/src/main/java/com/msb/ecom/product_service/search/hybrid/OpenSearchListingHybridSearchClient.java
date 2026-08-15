package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.ListingVectorBackfillProperties;
import com.msb.ecom.product_service.search.OpenSearchListingVectorBackfillClient;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OpenSearchListingHybridSearchClient {

    static final int BRANCH_LIMIT = 60;
    static final int MAX_RESPONSE_BYTES = 512_000;
    private static final Pattern PRODUCT_LISTING_ID =
            Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final ListingSearchProperties searchProperties;
    private final ListingVectorBackfillProperties backfillProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OpenSearchListingHybridSearchClient(
            ListingSearchProperties searchProperties,
            ListingVectorBackfillProperties backfillProperties,
            ObjectMapper objectMapper) {
        this(
                searchProperties,
                backfillProperties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(defaultDuration(
                                searchProperties.opensearch().connectTimeout(),
                                Duration.ofSeconds(1)))
                        .build());
    }

    OpenSearchListingHybridSearchClient(
            ListingSearchProperties searchProperties,
            ListingVectorBackfillProperties backfillProperties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.searchProperties = searchProperties;
        this.backfillProperties = backfillProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    // Verifies the stable read alias points to one exact compatible Product V2 generation.
    public String validateReadAlias() {
        String target = exactAliasTarget();
        validateOwnedV2Generation(target);
        HttpResponse<String> response = send("GET", "/" + target + "/_mapping", null);
        requireSuccess(response);
        JsonNode mapping = parse(response.body()).path(target).path("mappings");
        JsonNode metadata = mapping.path("_meta");
        JsonNode fields = mapping.path("properties");
        JsonNode embedding = mapping.path("properties").path("embedding");
        JsonNode method = embedding.path("method");
        if (!"strict".equals(mapping.path("dynamic").asText())
                || !OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY.equals(
                metadata.path("schemaIdentity").asText())
                || OpenSearchListingVectorBackfillClient.SCHEMA_VERSION
                != metadata.path("schemaVersion").asInt(-1)
                || !ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(
                metadata.path("embeddingProvider").asText())
                || !ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(
                metadata.path("embeddingModel").asText())
                || ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS
                != metadata.path("embeddingDimensions").asInt(-1)
                || !OpenSearchListingVectorBackfillClient.ENGINE.equals(
                metadata.path("engine").asText())
                || !OpenSearchListingVectorBackfillClient.DISTANCE_SPACE.equals(
                metadata.path("distanceSpace").asText())
                || !"knn_vector".equals(embedding.path("type").asText())
                || ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS
                != embedding.path("dimension").asInt(-1)
                || !OpenSearchListingVectorBackfillClient.ENGINE.equals(
                method.path("engine").asText())
                || !OpenSearchListingVectorBackfillClient.DISTANCE_SPACE.equals(
                method.path("space_type").asText())
                || !sourceExcludesEmbedding(mapping.path("_source"))
                || !requiredFieldsCompatible(fields)) {
            throw new ListingHybridSearchIdentityMismatchException();
        }
        HttpResponse<String> settingsResponse =
                send("GET", "/" + target + "/_settings", null);
        requireSuccess(settingsResponse);
        JsonNode settings = parse(settingsResponse.body())
                .path(target)
                .path("settings")
                .path("index");
        if (!settings.path("knn").asBoolean(false)) {
            throw new ListingHybridSearchIdentityMismatchException();
        }
        return target;
    }

    // Runs only the fixed Product-owned BM25 branch against the configured stable read alias.
    public List<String> searchLexical(ListingHybridSearchRequest request) {
        return search(searchProperties.opensearch().index(), lexicalBody(request));
    }

    // Runs only the fixed Product-owned BM25 branch against the validated V2 generation for one request.
    List<String> searchLexical(String target, ListingHybridSearchRequest request) {
        validateOwnedV2Generation(target);
        return search(target, lexicalBody(request));
    }

    // Runs only the fixed Product-owned filtered k-NN branch against the configured stable read alias.
    public List<String> searchVector(ListingHybridSearchRequest request) {
        return search(searchProperties.opensearch().index(), vectorBody(request));
    }

    // Runs only the fixed Product-owned filtered k-NN branch against the validated V2 generation for one request.
    List<String> searchVector(String target, ListingHybridSearchRequest request) {
        validateOwnedV2Generation(target);
        return search(target, vectorBody(request));
    }

    String lexicalBody(ListingHybridSearchRequest request) {
        ObjectNode root = baseSearch();
        ObjectNode bool = root.putObject("query").putObject("bool");
        if (ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION.equals(
                request.responseSchemaVersion())) {
            ArrayNode should = bool.putArray("should");
            for (String candidate : ListingQueryConcept.interpret(request.query())
                    .lexicalCandidates()) {
                lexicalMatch(should.addObject().putObject("multi_match"), candidate);
            }
            bool.put("minimum_should_match", 1);
        } else {
            lexicalMatch(bool.putArray("must").addObject().putObject("multi_match"),
                    request.query());
        }
        filters(bool.putArray("filter"), request.filters());
        stableSort(root);
        return root.toString();
    }

    private void lexicalMatch(ObjectNode match, String query) {
        match.put("query", query);
        match.put("type", "best_fields");
        match.put("operator", "and");
        ArrayNode fields = match.putArray("fields");
        fields.add("title^4");
        fields.add("categoryName^2");
        fields.add("searchText^2");
        fields.add("description");
    }

    String vectorBody(ListingHybridSearchRequest request) {
        ObjectNode root = baseSearch();
        ObjectNode definition = root.putObject("query")
                .putObject("knn")
                .putObject("embedding");
        ArrayNode vector = definition.putArray("vector");
        for (float value : request.embedding()) {
            vector.add(value);
        }
        definition.put("k", BRANCH_LIMIT);
        ObjectNode bool = definition.putObject("filter").putObject("bool");
        filters(bool.putArray("filter"), request.filters());
        return root.toString();
    }

    private ObjectNode baseSearch() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", BRANCH_LIMIT);
        root.put("_source", false);
        root.putArray("stored_fields");
        root.put("track_total_hits", false);
        return root;
    }

    private void stableSort(ObjectNode root) {
        ArrayNode sort = root.putArray("sort");
        sort.addObject().putObject("_score").put("order", "desc");
        sort.addObject().putObject("listingId").put("order", "asc");
    }

    private void filters(ArrayNode filters, ListingHybridSearchRequest.Filters request) {
        term(filters, "sellerType", "INDIVIDUAL");
        term(filters, "status", "ACTIVE");
        term(filters, "visibility", "PUBLIC");
        booleanTerm(filters, "inventoryAvailable", true);
        optionalTerm(filters, "categoryId", request.categoryId());
        optionalTerm(filters, "condition", request.condition());
        optionalTerm(filters, "currency", request.currency());
        optionalTerm(filters, "publicCityKey", key(request.city()));
        optionalTerm(filters, "publicRegionKey", key(request.publicRegion()));
        if (request.minPrice() != null || request.maxPrice() != null) {
            ObjectNode range = filters.addObject()
                    .putObject("range")
                    .putObject("priceAmount");
            if (request.minPrice() != null) {
                range.put("gte", request.minPrice());
            }
            if (request.maxPrice() != null) {
                range.put("lte", request.maxPrice());
            }
        }
    }

    private List<String> search(String target, String body) {
        HttpResponse<String> response = send(
                "POST",
                "/" + target + "/_search",
                body);
        requireSuccess(response);
        JsonNode hits = parse(response.body()).path("hits").path("hits");
        if (!hits.isArray() || hits.size() > BRANCH_LIMIT) {
            throw new ListingHybridSearchUnavailableException(
                    ListingHybridSearchFailureKind.HITS_SHAPE_INVALID);
        }
        List<String> ids = new ArrayList<>(hits.size());
        Set<String> unique = new HashSet<>();
        for (JsonNode hit : hits) {
            String id = hit.path("_id").asText(null);
            if (id == null || !PRODUCT_LISTING_ID.matcher(id).matches()) {
                throw new ListingHybridSearchUnavailableException(
                        ListingHybridSearchFailureKind.HIT_ID_INVALID);
            }
            if (unique.add(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private String exactAliasTarget() {
        HttpResponse<String> response = send(
                "GET",
                "/_alias/" + searchProperties.opensearch().index(),
                null);
        requireSuccess(response);
        JsonNode root = parse(response.body());
        if (!root.isObject() || root.size() != 1) {
            throw new ListingHybridSearchIdentityMismatchException();
        }
        String target = root.fieldNames().next();
        JsonNode alias = root.path(target)
                .path("aliases")
                .path(searchProperties.opensearch().index());
        if (alias.isMissingNode()) {
            throw new ListingHybridSearchIdentityMismatchException();
        }
        return target;
    }

    private void validateOwnedV2Generation(String target) {
        String prefix = backfillProperties.physicalIndexPrefix() + "-g";
        if (target == null
                || !target.startsWith(prefix)
                || target.length() != prefix.length() + 17
                || !target.substring(prefix.length()).chars().allMatch(Character::isDigit)) {
            throw new ListingHybridSearchIdentityMismatchException();
        }
    }

    private boolean sourceExcludesEmbedding(JsonNode source) {
        JsonNode excludes = source.path("excludes");
        if (!excludes.isArray()) {
            return false;
        }
        for (JsonNode value : excludes) {
            if ("embedding".equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean requiredFieldsCompatible(JsonNode fields) {
        return fieldType(fields, "listingId", "keyword")
                && fieldType(fields, "sellerType", "keyword")
                && fieldType(fields, "categoryId", "keyword")
                && fieldType(fields, "categoryName", "text")
                && fieldType(fields, "title", "text")
                && fieldType(fields, "description", "text")
                && fieldType(fields, "searchText", "text")
                && fieldType(fields, "condition", "keyword")
                && fieldType(fields, "priceAmount", "double")
                && fieldType(fields, "currency", "keyword")
                && fieldType(fields, "publicCityKey", "keyword")
                && fieldType(fields, "publicRegionKey", "keyword")
                && fieldType(fields, "status", "keyword")
                && fieldType(fields, "visibility", "keyword")
                && fieldType(fields, "inventoryAvailable", "boolean");
    }

    private boolean fieldType(JsonNode fields, String field, String type) {
        return type.equals(fields.path(field).path("type").asText());
    }

    private JsonNode parse(String body) {
        if (body == null
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new ListingHybridSearchUnavailableException(
                    ListingHybridSearchFailureKind.RESPONSE_OVERSIZE_OR_MISSING);
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new ListingHybridSearchUnavailableException(
                    ListingHybridSearchFailureKind.RESPONSE_JSON_INVALID);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) {
        Duration timeout = defaultDuration(
                searchProperties.opensearch().requestTimeout(),
                Duration.ofSeconds(3));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(timeout);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ListingHybridSearchUnavailableException(
                    ListingHybridSearchFailureKind.TRANSPORT_IO);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ListingHybridSearchUnavailableException(
                    ListingHybridSearchFailureKind.TRANSPORT_INTERRUPTED);
        }
    }

    private URI uri(String path) {
        String base = searchProperties.opensearch().baseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + path);
    }

    private void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ListingHybridSearchUnavailableException(
                    response.statusCode() >= 500
                            ? ListingHybridSearchFailureKind.HTTP_5XX
                            : ListingHybridSearchFailureKind.HTTP_4XX);
        }
    }

    private void term(ArrayNode filters, String field, String value) {
        filters.addObject().putObject("term").put(field, value);
    }

    private void booleanTerm(ArrayNode filters, String field, boolean value) {
        filters.addObject().putObject("term").put(field, value);
    }

    private void optionalTerm(ArrayNode filters, String field, String value) {
        if (value != null) {
            term(filters, field, value);
        }
    }

    private String key(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
