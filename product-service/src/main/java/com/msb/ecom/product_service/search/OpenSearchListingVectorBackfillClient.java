package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class OpenSearchListingVectorBackfillClient {

    public static final int SCHEMA_VERSION = 2;
    public static final String SCHEMA_IDENTITY = "marketplace-public-listing-v2-vector";
    public static final String DISTANCE_SPACE = "cosinesimil";
    public static final String ENGINE = "lucene";
    public static final int HNSW_M = 16;
    public static final int HNSW_EF_CONSTRUCTION = 100;
    private static final DateTimeFormatter GENERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ListingSearchProperties searchProperties;
    private final ListingVectorBackfillProperties backfillProperties;
    private final ObjectMapper objectMapper;
    private final ListingSearchProjectionMetrics metrics;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public OpenSearchListingVectorBackfillClient(
            ListingSearchProperties searchProperties,
            ListingVectorBackfillProperties backfillProperties,
            ObjectMapper objectMapper,
            ListingSearchProjectionMetrics metrics) {
        this(
                searchProperties,
                backfillProperties,
                objectMapper,
                metrics,
                HttpClient.newBuilder()
                        .connectTimeout(defaultDuration(
                                searchProperties.opensearch().connectTimeout(),
                                Duration.ofSeconds(1)))
                        .build(),
                Clock.systemUTC());
    }

    OpenSearchListingVectorBackfillClient(
            ListingSearchProperties searchProperties,
            ListingVectorBackfillProperties backfillProperties,
            ObjectMapper objectMapper,
            ListingSearchProjectionMetrics metrics,
            HttpClient httpClient,
            Clock clock) {
        this.searchProperties = searchProperties;
        this.backfillProperties = backfillProperties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public boolean enabled() {
        return backfillProperties.enabled() && searchProperties.openSearchEnabled();
    }

    // Separates reusable V2 document-write capability from the executable backfill command gate.
    public boolean vectorWritesAvailable() {
        return searchProperties.openSearchEnabled();
    }

    // Allocates a fresh generation name without creating or aliasing it.
    public String allocateGenerationName() {
        return generationName();
    }

    // Creates an empty owned V2 candidate and verifies that it is not visible through any alias.
    public void createInactiveGeneration(String generation) {
        requireOwnedGeneration(generation);
        requireHealthy();
        if (exists(generation)) {
            throw new ListingSearchUnavailableException(
                    "Listing vector generation already exists.");
        }
        requireSuccess(send("PUT", "/" + generation, indexDefinitionJson(), "application/json"),
                "OpenSearch vector generation creation");
        validateMapping(generation);
        validateCount(generation, 0, null);
        requireNoAliases(generation);
    }

    // Backfills an already registered candidate; external versions keep newer dual-writes authoritative.
    public void backfillRegisteredGeneration(
            String generation,
            List<ListingVectorBackfillDocument> documents) {
        requireOwnedGeneration(generation);
        validateDocuments(documents);
        validateMapping(generation);
        bulkIndex(generation, documents, true);
        refresh(generation);
    }

    // Applies a current lexical state to the exact candidate with checked Product ordering.
    public void upsertCandidate(String generation, ListingVectorBackfillDocument document) {
        requireOwnedGeneration(generation);
        validateDocuments(List.of(document));
        HttpResponse<String> response = send(
                "PUT",
                "/" + generation + "/_doc/" + document.listing().listingId()
                        + "?version=" + ListingVectorExternalVersion.lexical(document.listingVersion())
                        + "&version_type=external",
                documentJson(document),
                "application/json");
        requireSuccessOrVersionConflict(response, "OpenSearch candidate upsert");
    }

    // Resolves only exact durable V2 targets; the V1 alias remains a valid no-vector state.
    public VectorTargets resolveVectorTargets(Optional<String> candidateGeneration) {
        AliasState aliases = stableAliasState();
        if (!aliases.readGeneration().equals(aliases.writeGeneration())) {
            throw new ListingSearchVectorTargetIncompatibleException();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        String active = aliases.writeGeneration();
        if (isOwnedGeneration(active)) {
            validateLiveVectorTarget(active);
            targets.add(active);
        } else if (!isV1Generation(active)) {
            throw new ListingSearchVectorTargetIncompatibleException();
        }
        candidateGeneration.ifPresent(candidate -> {
            requireOwnedGeneration(candidate);
            validateLiveVectorTarget(candidate);
            targets.add(candidate);
        });
        return new VectorTargets(List.copyOf(targets));
    }

    // Writes a complete current document at the reserved semantic external version.
    public void upsertVectorTarget(
            String generation,
            ListingVectorBackfillDocument document) {
        requireOwnedGeneration(generation);
        validateDocuments(List.of(document));
        if (!document.hasEmbedding()) {
            throw new IllegalArgumentException(
                    "Listing vector target document must include an embedding.");
        }
        validateLiveVectorTarget(generation);
        HttpResponse<String> response = send(
                "PUT",
                "/" + generation + "/_doc/" + document.listing().listingId()
                        + "?version=" + ListingVectorExternalVersion.vector(document.listingVersion())
                        + "&version_type=external",
                documentJson(document),
                "application/json");
        requireSuccessOrVersionConflict(response, "OpenSearch vector target upsert");
    }

    // Removes an ineligible candidate state without allowing an older state to reappear.
    public void deleteCandidate(String generation, String listingId, long listingVersion) {
        requireOwnedGeneration(generation);
        HttpResponse<String> response = send(
                "DELETE",
                "/" + generation + "/_doc/" + listingId
                        + "?version=" + ListingVectorExternalVersion.lexical(listingVersion)
                        + "&version_type=external",
                null,
                null);
        if (response.statusCode() != 404) {
            requireSuccessOrVersionConflict(response, "OpenSearch candidate delete");
        }
    }

    public void validateCandidate(String generation, int expectedDocuments, int expectedVectors) {
        requireOwnedGeneration(generation);
        validateMapping(generation);
        refresh(generation);
        validateCount(generation, expectedDocuments, null);
        validateCount(generation, expectedVectors, "embedding");
    }

    public AliasState stableAliasState() {
        return new AliasState(
                exactAliasTarget(searchProperties.opensearch().index()),
                exactAliasTarget(searchProperties.opensearch().writeAlias()));
    }

    public void requireCandidateUnaliased(String generation) {
        requireOwnedGeneration(generation);
        requireNoAliases(generation);
    }

    // Moves both stable aliases in one OpenSearch aliases request and verifies the exact outcome.
    public void promoteAliases(String previousGeneration, String candidateGeneration) {
        requireOwnedGeneration(candidateGeneration);
        requireExactGeneration(previousGeneration);
        AliasState before = stableAliasState();
        if (!previousGeneration.equals(before.readGeneration())
                || !previousGeneration.equals(before.writeGeneration())) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch promotion alias precondition failed.");
        }
        requireNoAliases(candidateGeneration);
        requireSuccess(send(
                        "POST",
                        "/_aliases",
                        aliasMoveJson(previousGeneration, candidateGeneration),
                        "application/json"),
                "OpenSearch atomic alias promotion");
        requireStableAliases(candidateGeneration);
    }

    // Performs the single bounded inverse aliases request used after a failed post-promotion validation.
    public void rollbackAliases(String candidateGeneration, String previousGeneration) {
        requireOwnedGeneration(candidateGeneration);
        requireExactGeneration(previousGeneration);
        AliasState current = stableAliasState();
        if (!candidateGeneration.equals(current.readGeneration())
                || !candidateGeneration.equals(current.writeGeneration())) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch rollback alias precondition failed.");
        }
        requireSuccess(send(
                        "POST",
                        "/_aliases",
                        aliasMoveJson(candidateGeneration, previousGeneration),
                        "application/json"),
                "OpenSearch atomic alias rollback");
        requireStableAliases(previousGeneration);
    }

    // Builds and validates only a newly owned inactive generation; this type has no alias mutation operation.
    public InactiveGeneration backfill(List<ListingVectorBackfillDocument> documents) {
        if (!enabled()) {
            throw new ListingSearchUnavailableException("Listing vector backfill is disabled.");
        }
        validateDocuments(documents);
        String generation = generationName();
        JsonNode aliasesBefore = aliasSnapshot();
        boolean created = false;
        try {
            requireHealthy();
            if (exists(generation)) {
                throw new ListingSearchUnavailableException(
                        "Listing vector generation already exists.");
            }
            requireSuccess(send("PUT", "/" + generation, indexDefinitionJson(), "application/json"),
                    "OpenSearch vector generation creation");
            created = true;
            validateMapping(generation);
            validateCount(generation, 0, null);
            bulkIndex(generation, documents, false);
            refresh(generation);
            int vectorCount = (int) documents.stream()
                    .filter(ListingVectorBackfillDocument::hasEmbedding)
                    .count();
            validateCount(generation, documents.size(), null);
            validateCount(generation, vectorCount, "embedding");
            requireAliasesUnchanged(aliasesBefore);
            metrics.record("vector_backfill", "inactive_validated");
            log.info(
                    "Listing vector backfill validated status=INACTIVE_VALIDATED documents={} vectorDocuments={}",
                    documents.size(),
                    vectorCount);
            return new InactiveGeneration(generation, documents.size(), vectorCount);
        } catch (RuntimeException exception) {
            metrics.record("vector_backfill", "failure");
            if (created && backfillProperties.cleanupFailedGeneration()) {
                cleanupOwnedGeneration(generation);
            }
            requireAliasesUnchanged(aliasesBefore);
            throw exception;
        }
    }

    String indexDefinitionJson() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode settings = root.putObject("settings");
        settings.put("index.knn", true);
        settings.put("index.number_of_shards", 1);
        settings.put("index.number_of_replicas", 0);
        ObjectNode mappings = root.putObject("mappings");
        mappings.put("dynamic", "strict");
        mappings.putObject("_source").putArray("excludes").add("embedding");
        mappings.putObject("_meta")
                .put("projection", "marketplace-public-listing")
                .put("schemaVersion", SCHEMA_VERSION)
                .put("schemaIdentity", SCHEMA_IDENTITY)
                .put("documentSchemaVersion", ListingDiscoveryEmbeddingSourceBuilder.DOCUMENT_SCHEMA_VERSION)
                .put("embeddingInputSchemaVersion", ListingDiscoveryEmbeddingSourceBuilder.INPUT_SCHEMA_VERSION)
                .put("normalizerVersion", ListingDiscoveryEmbeddingSourceBuilder.NORMALIZER_VERSION)
                .put("redactorVersion", ListingDiscoveryEmbeddingSourceBuilder.REDACTOR_VERSION)
                .put("language", ListingDiscoveryEmbeddingSourceBuilder.LANGUAGE)
                .put("embeddingProvider", ListingDiscoveryEmbeddingSourceBuilder.PROVIDER)
                .put("embeddingModel", ListingDiscoveryEmbeddingSourceBuilder.MODEL)
                .put("embeddingDimensions", ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS)
                .put("engine", ENGINE)
                .put("distanceSpace", DISTANCE_SPACE)
                .put("hnswM", HNSW_M)
                .put("hnswEfConstruction", HNSW_EF_CONSTRUCTION)
                .put("createdBy", "product-service");
        ObjectNode fields = mappings.putObject("properties");
        keyword(fields, "listingId");
        fields.putObject("listingVersion").put("type", "long");
        keyword(fields, "sellerType");
        keyword(fields, "categoryId");
        keyword(fields, "categorySlug");
        textWithKeyword(fields, "categoryName");
        textWithKeyword(fields, "title");
        text(fields, "description");
        text(fields, "searchText");
        keyword(fields, "condition");
        fields.putObject("priceAmount").put("type", "double");
        keyword(fields, "currency");
        keyword(fields, "publicCity");
        keyword(fields, "publicCityKey");
        keyword(fields, "publicRegion");
        keyword(fields, "publicRegionKey");
        keyword(fields, "status");
        keyword(fields, "visibility");
        fields.putObject("inventoryAvailable").put("type", "boolean");
        keyword(fields, "primaryImageUrl");
        fields.putObject("publishedAt").put("type", "date");
        keyword(fields, "documentSchemaVersion");
        keyword(fields, "documentHash");
        keyword(fields, "embeddingInputSchemaVersion");
        keyword(fields, "embeddingInputHash");
        keyword(fields, "normalizerVersion");
        keyword(fields, "redactorVersion");
        keyword(fields, "language");
        keyword(fields, "embeddingProvider");
        keyword(fields, "embeddingModel");
        fields.putObject("embeddingDimensions").put("type", "integer");
        keyword(fields, "embeddingState");
        ObjectNode vector = fields.putObject("embedding");
        vector.put("type", "knn_vector");
        vector.put("dimension", ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS);
        ObjectNode method = vector.putObject("method");
        method.put("name", "hnsw");
        method.put("space_type", DISTANCE_SPACE);
        method.put("engine", ENGINE);
        method.putObject("parameters")
                .put("ef_construction", HNSW_EF_CONSTRUCTION)
                .put("m", HNSW_M);
        return root.toString();
    }

    private String documentJson(ListingVectorBackfillDocument document) {
        ListingSearchDocument listing = document.listing();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("listingId", listing.listingId());
        root.put("listingVersion", document.listingVersion());
        root.put("sellerType", listing.sellerType());
        root.put("categoryId", listing.categoryId());
        put(root, "categorySlug", listing.categorySlug());
        put(root, "categoryName", listing.categoryName());
        root.put("title", listing.title());
        put(root, "description", listing.description());
        root.put("searchText", listing.searchText());
        root.put("condition", listing.condition());
        root.put("priceAmount", listing.priceAmount());
        root.put("currency", listing.currency());
        put(root, "publicCity", listing.publicCity());
        put(root, "publicCityKey", listing.publicCityKey());
        put(root, "publicRegion", listing.publicRegion());
        put(root, "publicRegionKey", listing.publicRegionKey());
        root.put("status", "ACTIVE");
        root.put("visibility", "PUBLIC");
        root.put("inventoryAvailable", listing.inventoryAvailable());
        put(root, "primaryImageUrl", listing.primaryImageUrl());
        root.put("publishedAt", listing.publishedAt().toString());
        put(root, "documentSchemaVersion", document.documentSchemaVersion());
        put(root, "documentHash", document.documentHash());
        put(root, "embeddingInputSchemaVersion", document.embeddingInputSchemaVersion());
        put(root, "embeddingInputHash", document.embeddingInputHash());
        put(root, "normalizerVersion", document.normalizerVersion());
        put(root, "redactorVersion", document.redactorVersion());
        put(root, "language", document.language());
        put(root, "embeddingProvider", document.embeddingProvider());
        put(root, "embeddingModel", document.embeddingModel());
        if (document.embeddingDimensions() > 0) {
            root.put("embeddingDimensions", document.embeddingDimensions());
        }
        root.put("embeddingState", document.embeddingState());
        if (document.hasEmbedding()) {
            ArrayNode embedding = root.putArray("embedding");
            for (float value : document.embedding()) {
                embedding.add(value);
            }
        }
        return root.toString();
    }

    private void bulkIndex(
            String generation,
            List<ListingVectorBackfillDocument> documents,
            boolean tolerateVersionConflicts) {
        List<String> operations = new ArrayList<>();
        int bytes = 0;
        for (ListingVectorBackfillDocument document : documents) {
            ObjectNode action = objectMapper.createObjectNode();
            ObjectNode index = action.putObject("index");
            index.put("_index", generation);
            index.put("_id", document.listing().listingId());
            index.put("version", document.hasEmbedding()
                    ? ListingVectorExternalVersion.vector(document.listingVersion())
                    : ListingVectorExternalVersion.lexical(document.listingVersion()));
            index.put("version_type", "external");
            String operation = action + "\n" + documentJson(document) + "\n";
            int operationBytes = operation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (operationBytes > backfillProperties.maxBulkBytes()) {
                throw new ListingSearchUnavailableException(
                        "Listing vector backfill operation exceeds its byte limit.");
            }
            if (!operations.isEmpty()
                    && (operations.size() == backfillProperties.batchSize()
                    || bytes + operationBytes > backfillProperties.maxBulkBytes())) {
                sendBulk(operations, tolerateVersionConflicts);
                operations.clear();
                bytes = 0;
            }
            operations.add(operation);
            bytes += operationBytes;
        }
        if (!operations.isEmpty()) {
            sendBulk(operations, tolerateVersionConflicts);
        }
    }

    private void sendBulk(List<String> operations, boolean tolerateVersionConflicts) {
        String body = String.join("", operations);
        HttpResponse<String> response = send("POST", "/_bulk", body, "application/x-ndjson");
        requireSuccess(response, "OpenSearch vector bulk indexing");
        try {
            JsonNode parsed = objectMapper.readTree(response.body());
            if (!parsed.path("errors").asBoolean(true)) {
                return;
            }
            if (!tolerateVersionConflicts || !onlyVersionConflicts(parsed.path("items"))) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch vector bulk indexing failed.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch vector bulk response was invalid JSON.", exception);
        }
    }

    private boolean onlyVersionConflicts(JsonNode items) {
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            JsonNode result = item.elements().hasNext()
                    ? item.elements().next()
                    : objectMapper.missingNode();
            if (result.path("status").asInt() >= 300
                    && !"version_conflict_engine_exception".equals(
                    result.path("error").path("type").asText())) {
                return false;
            }
        }
        return true;
    }

    private void validateDocuments(List<ListingVectorBackfillDocument> documents) {
        if (documents == null || documents.size() > backfillProperties.maxDocuments()) {
            throw new ListingSearchUnavailableException("Listing vector backfill documents are invalid.");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ListingVectorBackfillDocument document : documents) {
            if (document == null
                    || document.listing() == null
                    || document.listing().listingId() == null
                    || !ids.add(document.listing().listingId())
                    || document.embeddingState() == null
                    || (document.hasEmbedding()
                    && (!"ATTACHED".equals(document.embeddingState())
                    || document.embedding().length != ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS))) {
                throw new ListingSearchUnavailableException("Listing vector backfill document is incompatible.");
            }
            ListingVectorExternalVersion.lexical(document.listingVersion());
            if (document.hasEmbedding()) {
                for (float value : document.embedding()) {
                    if (!Float.isFinite(value)) {
                        throw new ListingSearchUnavailableException(
                                "Listing vector backfill document is incompatible.");
                    }
                }
            }
        }
    }

    private void requireHealthy() {
        HttpResponse<String> response = send(
                "GET", "/_cluster/health?wait_for_status=yellow&timeout=3s", null, null);
        requireSuccess(response, "OpenSearch vector cluster health");
        try {
            String status = objectMapper.readTree(response.body()).path("status").asText();
            if (!"yellow".equals(status) && !"green".equals(status)) {
                throw new ListingSearchUnavailableException("OpenSearch vector cluster is unhealthy.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch vector health response was invalid JSON.", exception);
        }
    }

    private boolean exists(String index) {
        HttpResponse<String> response = send("HEAD", "/" + index, null, null);
        if (response.statusCode() == 404) {
            return false;
        }
        requireSuccess(response, "OpenSearch vector generation existence check");
        return true;
    }

    private void validateMapping(String generation) {
        HttpResponse<String> response = send("GET", "/" + generation + "/_mapping", null, null);
        requireSuccess(response, "OpenSearch vector mapping validation");
        try {
            JsonNode actual = objectMapper.readTree(response.body()).path(generation).path("mappings");
            JsonNode expected = objectMapper.readTree(indexDefinitionJson()).path("mappings");
            if (!expected.equals(actual)) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch vector mapping identity is incompatible.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch vector mapping response was invalid JSON.", exception);
        }
    }

    private void validateLiveVectorTarget(String generation) {
        HttpResponse<String> response = send("GET", "/" + generation + "/_mapping", null, null);
        requireSuccess(response, "OpenSearch vector target mapping validation");
        try {
            JsonNode actual = objectMapper.readTree(response.body()).path(generation).path("mappings");
            JsonNode expected = objectMapper.readTree(indexDefinitionJson()).path("mappings");
            if (!expected.equals(actual)) {
                throw new ListingSearchVectorTargetIncompatibleException();
            }
        } catch (IOException exception) {
            throw new ListingSearchVectorTargetIncompatibleException();
        }
    }

    private void validateCount(String generation, int expected, String requiredField) {
        String body = null;
        if (requiredField != null) {
            ObjectNode countRequest = objectMapper.createObjectNode();
            countRequest.putObject("query").putObject("exists").put("field", requiredField);
            body = countRequest.toString();
        }
        HttpResponse<String> response = send(
                requiredField == null ? "GET" : "POST",
                "/" + generation + "/_count",
                body,
                requiredField == null ? null : "application/json");
        requireSuccess(response, "OpenSearch vector count validation");
        try {
            if (objectMapper.readTree(response.body()).path("count").asInt(-1) != expected) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch vector document count validation failed.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch vector count response was invalid JSON.", exception);
        }
    }

    private JsonNode aliasSnapshot() {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set(searchProperties.opensearch().index(), aliasResponse(searchProperties.opensearch().index()));
        snapshot.set(searchProperties.opensearch().writeAlias(), aliasResponse(searchProperties.opensearch().writeAlias()));
        return snapshot;
    }

    private JsonNode aliasResponse(String alias) {
        HttpResponse<String> response = send("GET", "/_alias/" + alias, null, null);
        if (response.statusCode() == 404) {
            return objectMapper.createObjectNode().put("missing", true);
        }
        requireSuccess(response, "OpenSearch active alias snapshot");
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch active alias response was invalid JSON.", exception);
        }
    }

    private String exactAliasTarget(String alias) {
        JsonNode response = aliasResponse(alias);
        if (response.path("missing").asBoolean(false)
                || !response.isObject()
                || response.size() != 1) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch stable alias is missing or ambiguous.");
        }
        Iterator<String> names = response.fieldNames();
        return names.next();
    }

    private void requireNoAliases(String generation) {
        HttpResponse<String> response = send("GET", "/" + generation + "/_alias", null, null);
        if (response.statusCode() == 404) {
            return;
        }
        requireSuccess(response, "OpenSearch candidate alias validation");
        try {
            JsonNode aliases = objectMapper.readTree(response.body())
                    .path(generation)
                    .path("aliases");
            if (!aliases.isObject() || aliases.size() != 0) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch candidate must not have aliases.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch candidate alias response was invalid JSON.", exception);
        }
    }

    private void requireStableAliases(String generation) {
        AliasState aliases = stableAliasState();
        if (!generation.equals(aliases.readGeneration())
                || !generation.equals(aliases.writeGeneration())) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch stable alias validation failed.");
        }
    }

    private String aliasMoveJson(String from, String to) {
        ArrayNode actions = objectMapper.createObjectNode().putArray("actions");
        actions.addObject().putObject("remove")
                .put("index", from)
                .put("alias", searchProperties.opensearch().index());
        actions.addObject().putObject("remove")
                .put("index", from)
                .put("alias", searchProperties.opensearch().writeAlias());
        actions.addObject().putObject("add")
                .put("index", to)
                .put("alias", searchProperties.opensearch().index());
        actions.addObject().putObject("add")
                .put("index", to)
                .put("alias", searchProperties.opensearch().writeAlias())
                .put("is_write_index", true);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("actions", actions);
        return root.toString();
    }

    private void refresh(String generation) {
        requireSuccess(send("POST", "/" + generation + "/_refresh", null, null),
                "OpenSearch vector generation refresh");
    }

    private void requireAliasesUnchanged(JsonNode before) {
        if (!before.equals(aliasSnapshot())) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch active aliases changed during inactive vector backfill.");
        }
    }

    private void cleanupOwnedGeneration(String generation) {
        try {
            HttpResponse<String> response = send("DELETE", "/" + generation, null, null);
            if (response.statusCode() != 404) {
                requireSuccess(response, "OpenSearch inactive vector generation cleanup");
            }
            metrics.record("vector_backfill_cleanup", "success");
        } catch (RuntimeException cleanupFailure) {
            metrics.record("vector_backfill_cleanup", "failure");
            log.warn("Inactive listing vector generation cleanup failed");
        }
    }

    private String generationName() {
        return backfillProperties.physicalIndexPrefix()
                + "-g"
                + GENERATION_TIME.format(clock.instant());
    }

    private void requireOwnedGeneration(String generation) {
        String prefix = backfillProperties.physicalIndexPrefix() + "-g";
        if (generation == null
                || !generation.startsWith(prefix)
                || generation.length() != prefix.length() + 17
                || !generation.substring(prefix.length()).chars().allMatch(Character::isDigit)) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch candidate generation is invalid.");
        }
    }

    private boolean isOwnedGeneration(String generation) {
        try {
            requireOwnedGeneration(generation);
            return true;
        } catch (ListingSearchUnavailableException exception) {
            return false;
        }
    }

    private boolean isV1Generation(String generation) {
        String base = searchProperties.opensearch().physicalIndex();
        if (base == null || generation == null) {
            return false;
        }
        if (generation.equals(base)) {
            return true;
        }
        String prefix = base + "-g";
        if (!generation.startsWith(prefix)) {
            return false;
        }
        String suffix = generation.substring(prefix.length());
        int separator = suffix.indexOf('-');
        String timestamp = separator < 0 ? suffix : suffix.substring(0, separator);
        String attempt = separator < 0 ? "" : suffix.substring(separator + 1);
        return timestamp.length() == 17
                && timestamp.chars().allMatch(Character::isDigit)
                && (attempt.isEmpty() || attempt.chars().allMatch(Character::isDigit));
    }

    private void requireExactGeneration(String generation) {
        if (generation == null
                || !generation.matches("[a-z0-9][a-z0-9._-]{1,254}")) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch generation identity is invalid.");
        }
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(defaultDuration(
                            searchProperties.opensearch().requestTimeout(),
                            Duration.ofSeconds(3)));
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch vector operation is unavailable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ListingSearchUnavailableException("OpenSearch vector operation was interrupted.", exception);
        }
    }

    private String baseUrl() {
        String configured = searchProperties.opensearch().baseUrl();
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ListingSearchUnavailableException(operation + " failed safely.");
        }
    }

    private void requireSuccessOrVersionConflict(
            HttpResponse<String> response,
            String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        if (response.statusCode() == 409) {
            try {
                if ("version_conflict_engine_exception".equals(
                        objectMapper.readTree(response.body())
                                .path("error")
                                .path("type")
                                .asText())) {
                    return;
                }
            } catch (IOException exception) {
                throw new ListingSearchUnavailableException(
                        operation + " returned an invalid conflict response.", exception);
            }
        }
        throw new ListingSearchUnavailableException(operation + " failed safely.");
    }

    private void put(ObjectNode root, String field, String value) {
        if (value != null) {
            root.put(field, value);
        }
    }

    private void keyword(ObjectNode fields, String name) {
        fields.putObject(name).put("type", "keyword");
    }

    private void text(ObjectNode fields, String name) {
        fields.putObject(name).put("type", "text");
    }

    private void textWithKeyword(ObjectNode fields, String name) {
        ObjectNode field = fields.putObject(name).put("type", "text");
        field.putObject("fields").putObject("keyword").put("type", "keyword");
    }

    private static Duration defaultDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() || configured.isZero()
                ? fallback
                : configured;
    }

    public record InactiveGeneration(
            String generation,
            int documentCount,
            int vectorDocumentCount
    ) {
    }

    public record AliasState(String readGeneration, String writeGeneration) {
    }

    public record VectorTargets(List<String> generations) {

        public VectorTargets {
            generations = List.copyOf(generations);
        }

        public boolean ready() {
            return !generations.isEmpty();
        }
    }
}
