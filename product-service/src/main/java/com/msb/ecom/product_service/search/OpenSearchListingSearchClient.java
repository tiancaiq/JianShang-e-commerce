package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class OpenSearchListingSearchClient {

    static final String PROJECTION_NAME = "marketplace-public-listing";
    static final int SCHEMA_VERSION = 1;
    static final String SCHEMA_IDENTITY = "marketplace-public-listing-v1-bm25";
    private static final DateTimeFormatter GENERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);
    private static final int BULK_BATCH_SIZE = 250;

    private final ListingSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ListingSearchProjectionMetrics metrics;
    private final HttpClient httpClient;
    private final Clock clock;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    @Autowired
    public OpenSearchListingSearchClient(
            ListingSearchProperties properties,
            ObjectMapper objectMapper,
            ListingSearchProjectionMetrics metrics) {
        this(
                properties,
                objectMapper,
                metrics,
                HttpClient.newBuilder()
                        .connectTimeout(defaultDuration(properties.opensearch().connectTimeout(), Duration.ofSeconds(1)))
                        .build(),
                Clock.systemUTC());
    }

    OpenSearchListingSearchClient(
            ListingSearchProperties properties,
            ObjectMapper objectMapper,
            ListingSearchProjectionMetrics metrics,
            HttpClient httpClient,
            Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.openSearchEnabled();
    }

    public String indexName() {
        return properties.opensearch().index();
    }

    public String writeAliasName() {
        return properties.opensearch().writeAlias();
    }

    public String physicalIndexName() {
        return properties.opensearch().physicalIndex();
    }

    // Initializes or validates the exact one-generation read/write alias boundary.
    public synchronized void ensureIndex() {
        if (!enabled() || !properties.opensearch().initializeIndex() || indexReady.get()) {
            return;
        }
        try {
            requireHealthy();
            AliasTarget read = aliasTarget(indexName(), false);
            AliasTarget write = aliasTarget(writeAliasName(), true);
            if (read.exists() || write.exists()) {
                String target = reconcilePartialAliases(read, write);
                validateCompatibleMapping(target);
            } else {
                initializeBaseline();
            }
            indexReady.set(true);
            metrics.record("initialize", "success");
            log.info("Listing search projection initialized");
        } catch (RuntimeException exception) {
            metrics.record("initialize", "failure");
            throw exception;
        }
    }

    public List<String> searchIds(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ensureIndex();
        try {
            HttpResponse<String> response = send(
                    "POST",
                    "/" + indexName() + "/_search",
                    searchJson(sellerType, criteria, limit),
                    "application/json");
            requireSuccess(response, "OpenSearch listing search");
            List<String> ids = responseIds(response.body());
            metrics.record("search", "success");
            return ids;
        } catch (RuntimeException exception) {
            metrics.record("search", "failure");
            throw exception;
        }
    }

    // Returns first-page BM25 candidates for the Agent; Product revalidates every ID in MySQL.
    public List<String> searchRelevantIds(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ensureIndex();
        try {
            HttpResponse<String> response = send(
                    "POST",
                    "/" + indexName() + "/_search",
                    relevanceSearchJson(sellerType, criteria, limit),
                    "application/json");
            requireSuccess(response, "OpenSearch listing relevance search");
            List<String> ids = responseIds(response.body());
            metrics.record("relevance_search", "success");
            return ids;
        } catch (RuntimeException exception) {
            metrics.record("relevance_search", "failure");
            throw exception;
        }
    }

    // Live projection writes always target the dedicated single-target write alias.
    public void upsert(ListingSearchDocument document) {
        upsert(document, null);
    }

    // Aggregate-version writes make retries and out-of-order delivery unable to overwrite newer documents.
    public void upsert(ListingSearchDocument document, long listingVersion) {
        upsert(document, Long.valueOf(listingVersion));
    }

    private void upsert(ListingSearchDocument document, Long listingVersion) {
        ensureIndex();
        validateDocument(document);
        try {
            HttpResponse<String> response = send(
                    "PUT",
                    versionedDocumentPath(document.listingId(), listingVersion),
                    documentJson(document),
                    "application/json");
            if (response.statusCode() != 409) {
                requireSuccess(response, "OpenSearch listing upsert");
            }
            metrics.record("upsert", "success");
        } catch (RuntimeException exception) {
            metrics.record("upsert", "failure");
            throw exception;
        }
    }

    public void delete(String listingId) {
        delete(listingId, null);
    }

    // External versions leave a delete tombstone that rejects delayed older upserts.
    public void delete(String listingId, long listingVersion) {
        delete(listingId, Long.valueOf(listingVersion));
    }

    private void delete(String listingId, Long listingVersion) {
        ensureIndex();
        try {
            HttpResponse<String> response = send(
                    "DELETE",
                    versionedDocumentPath(listingId, listingVersion),
                    null,
                    null);
            if (response.statusCode() != 404 && response.statusCode() != 409) {
                requireSuccess(response, "OpenSearch listing delete");
            }
            metrics.record("delete", "success");
        } catch (RuntimeException exception) {
            metrics.record("delete", "failure");
            throw exception;
        }
    }

    private String versionedDocumentPath(String listingId, Long listingVersion) {
        String path = "/" + writeAliasName() + "/_doc/" + listingId;
        return listingVersion == null
                ? path
                : path + "?version=" + listingVersion + "&version_type=external";
    }

    // Rebuilds a complete fresh generation and promotes it only after schema and count validation.
    public synchronized int rebuild(List<ListingSearchDocument> documents) {
        ensureIndex();
        validateDocuments(documents);
        AliasTarget currentRead = aliasTarget(indexName(), false);
        AliasTarget currentWrite = aliasTarget(writeAliasName(), true);
        requireSameTarget(currentRead, currentWrite);

        String generation = nextAvailableGeneration();
        boolean promoted = false;
        try {
            HttpResponse<String> created = send(
                    "PUT",
                    "/" + generation,
                    indexDefinitionJson(false),
                    "application/json");
            requireSuccess(created, "OpenSearch rebuild index creation");
            validateCompatibleMapping(generation);
            bulkIndex(generation, documents);
            refresh(generation);
            validateCount(generation, documents.size());
            promoteAliases(currentRead.target(), currentWrite.target(), generation);
            promoted = true;
            validatePromotedAliases(generation);
            metrics.record("rebuild", "success");
            log.info(
                    "Rebuilt listing search projection schemaVersion={} documentCount={}",
                    SCHEMA_VERSION,
                    documents.size());
            return documents.size();
        } catch (RuntimeException exception) {
            if (promoted) {
                rollbackAliases(generation, currentRead.target(), currentWrite.target());
            }
            metrics.record("rebuild", "failure");
            throw exception;
        }
    }

    private void requireHealthy() {
        HttpResponse<String> health = send(
                "GET",
                "/_cluster/health?wait_for_status=yellow&timeout=3s",
                null,
                null);
        requireSuccess(health, "OpenSearch health check");
    }

    private String reconcilePartialAliases(AliasTarget read, AliasTarget write) {
        if (read.exists() && write.exists()) {
            requireSameTarget(read, write);
            return read.target();
        }
        AliasTarget existing = read.exists() ? read : write;
        validateCompatibleMapping(existing.target());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        if (!read.exists()) {
            addAlias(actions, existing.target(), indexName(), false);
        }
        if (!write.exists()) {
            addAlias(actions, existing.target(), writeAliasName(), true);
        }
        requireSuccess(
                send("POST", "/_aliases", root.toString(), "application/json"),
                "OpenSearch alias reconciliation");
        return existing.target();
    }

    private void requireSameTarget(AliasTarget read, AliasTarget write) {
        if (!read.exists() || !write.exists() || !read.target().equals(write.target())) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch listing aliases must resolve to exactly one shared generation.");
        }
    }

    private void initializeBaseline() {
        HttpResponse<String> physical = send("HEAD", "/" + physicalIndexName(), null, null);
        if (physical.statusCode() == 404) {
            requireSuccess(
                    send(
                            "PUT",
                            "/" + physicalIndexName(),
                            indexDefinitionJson(true),
                            "application/json"),
                    "OpenSearch baseline index creation");
            return;
        }
        requireSuccess(physical, "OpenSearch baseline index check");
        validateCompatibleMapping(physicalIndexName());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        addAlias(actions, physicalIndexName(), indexName(), false);
        addAlias(actions, physicalIndexName(), writeAliasName(), true);
        requireSuccess(
                send("POST", "/_aliases", root.toString(), "application/json"),
                "OpenSearch baseline alias creation");
    }

    private AliasTarget aliasTarget(String alias, boolean requireWriteIndex) {
        HttpResponse<String> response = send("GET", "/_alias/" + alias, null, null);
        if (response.statusCode() == 404) {
            return AliasTarget.absent();
        }
        requireSuccess(response, "OpenSearch alias validation");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isObject() || root.size() != 1) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch listing alias must resolve to exactly one generation.");
            }
            String target = root.fieldNames().next();
            JsonNode aliasDefinition = root.path(target).path("aliases").path(alias);
            if (target.isBlank()
                    || aliasDefinition.isMissingNode()
                    || (requireWriteIndex && !aliasDefinition.path("is_write_index").asBoolean(false))) {
                throw new ListingSearchUnavailableException("OpenSearch listing alias response is incompatible.");
            }
            return new AliasTarget(true, target);
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch alias response was invalid JSON.", exception);
        }
    }

    private void validateCompatibleMapping(String index) {
        HttpResponse<String> response = send("GET", "/" + index + "/_mapping", null, null);
        requireSuccess(response, "OpenSearch listing mapping validation");
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode mapping = root.path(index).path("mappings");
            JsonNode metadata = mapping.path("_meta");
            if (!compatibleProjectionIdentity(metadata)
                    || !"strict".equals(mapping.path("dynamic").asText())
                    || !requiredMappingFieldsCompatible(mapping.path("properties"))) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch listing projection schema is incompatible.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch mapping response was invalid JSON.", exception);
        }
    }

    // Public BM25 search can run on the original V1 mapping or the approved V2 vector superset.
    private boolean compatibleProjectionIdentity(JsonNode metadata) {
        if (!PROJECTION_NAME.equals(metadata.path("projection").asText())) {
            return false;
        }
        int schemaVersion = metadata.path("schemaVersion").asInt(-1);
        String schemaIdentity = metadata.path("schemaIdentity").asText();
        return (SCHEMA_VERSION == schemaVersion && SCHEMA_IDENTITY.equals(schemaIdentity))
                || (OpenSearchListingVectorBackfillClient.SCHEMA_VERSION == schemaVersion
                && OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY.equals(schemaIdentity));
    }

    private boolean requiredMappingFieldsCompatible(JsonNode fields) {
        return fieldType(fields, "listingId", "keyword")
                && fieldType(fields, "sellerType", "keyword")
                && fieldType(fields, "title", "text")
                && fieldType(fields, "description", "text")
                && fieldType(fields, "searchText", "text")
                && fieldType(fields, "categoryId", "keyword")
                && fieldType(fields, "categorySlug", "keyword")
                && fieldType(fields, "categoryName", "text")
                && fieldType(fields, "condition", "keyword")
                && fieldType(fields, "priceAmount", "double")
                && fieldType(fields, "currency", "keyword")
                && fieldType(fields, "publicCity", "keyword")
                && fieldType(fields, "publicCityKey", "keyword")
                && fieldType(fields, "publicRegion", "keyword")
                && fieldType(fields, "publicRegionKey", "keyword")
                && fieldType(fields, "status", "keyword")
                && fieldType(fields, "visibility", "keyword")
                && fieldType(fields, "inventoryAvailable", "boolean")
                && fieldType(fields, "publishedAt", "date");
    }

    private boolean fieldType(JsonNode fields, String name, String expected) {
        return expected.equals(fields.path(name).path("type").asText());
    }

    private String nextAvailableGeneration() {
        String base = physicalIndexName() + "-g" + GENERATION_TIME.format(clock.instant());
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = attempt == 0 ? base : base + "-" + attempt;
            HttpResponse<String> response = send("HEAD", "/" + candidate, null, null);
            if (response.statusCode() == 404) {
                return candidate;
            }
            requireSuccess(response, "OpenSearch rebuild generation check");
        }
        throw new ListingSearchUnavailableException("OpenSearch rebuild generation allocation failed.");
    }

    private void bulkIndex(String generation, List<ListingSearchDocument> documents) {
        for (int offset = 0; offset < documents.size(); offset += BULK_BATCH_SIZE) {
            int end = Math.min(offset + BULK_BATCH_SIZE, documents.size());
            StringBuilder body = new StringBuilder();
            for (ListingSearchDocument document : documents.subList(offset, end)) {
                ObjectNode action = objectMapper.createObjectNode();
                ObjectNode index = action.putObject("index");
                index.put("_index", generation);
                index.put("_id", document.listingId());
                body.append(action).append('\n');
                body.append(documentJson(document)).append('\n');
            }
            HttpResponse<String> response = send("POST", "/_bulk", body.toString(), "application/x-ndjson");
            requireSuccess(response, "OpenSearch rebuild bulk indexing");
            try {
                if (objectMapper.readTree(response.body()).path("errors").asBoolean(true)) {
                    throw new ListingSearchUnavailableException("OpenSearch rebuild bulk indexing failed.");
                }
            } catch (IOException exception) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch rebuild bulk response was invalid JSON.",
                        exception);
            }
        }
    }

    private void refresh(String generation) {
        requireSuccess(
                send("POST", "/" + generation + "/_refresh", null, null),
                "OpenSearch rebuild refresh");
    }

    private void validateCount(String generation, int expected) {
        HttpResponse<String> response = send("GET", "/" + generation + "/_count", null, null);
        requireSuccess(response, "OpenSearch rebuild count validation");
        try {
            int actual = objectMapper.readTree(response.body()).path("count").asInt(-1);
            if (actual != expected) {
                throw new ListingSearchUnavailableException(
                        "OpenSearch rebuild document count validation failed.");
            }
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException(
                    "OpenSearch rebuild count response was invalid JSON.",
                    exception);
        }
    }

    private void promoteAliases(String oldRead, String oldWrite, String generation) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        removeAlias(actions, oldRead, indexName());
        removeAlias(actions, oldWrite, writeAliasName());
        addAlias(actions, generation, indexName(), false);
        addAlias(actions, generation, writeAliasName(), true);
        requireSuccess(
                send("POST", "/_aliases", root.toString(), "application/json"),
                "OpenSearch rebuild alias promotion");
    }

    private void validatePromotedAliases(String generation) {
        AliasTarget read = aliasTarget(indexName(), false);
        AliasTarget write = aliasTarget(writeAliasName(), true);
        requireSameTarget(read, write);
        if (!generation.equals(read.target())) {
            throw new ListingSearchUnavailableException("OpenSearch rebuild alias promotion was not applied.");
        }
        validateCompatibleMapping(generation);
    }

    private void rollbackAliases(String generation, String oldRead, String oldWrite) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        removeAlias(actions, generation, indexName());
        removeAlias(actions, generation, writeAliasName());
        addAlias(actions, oldRead, indexName(), false);
        addAlias(actions, oldWrite, writeAliasName(), true);
        try {
            requireSuccess(
                    send("POST", "/_aliases", root.toString(), "application/json"),
                    "OpenSearch rebuild alias rollback");
            metrics.record("rollback", "success");
            log.warn("Rolled back listing search projection alias promotion");
        } catch (RuntimeException rollbackFailure) {
            metrics.record("rollback", "failure");
            log.error("Listing search projection alias rollback failed");
        }
    }

    private ObjectNode addAlias(ArrayNode actions, String index, String alias, boolean write) {
        ObjectNode add = actions.addObject().putObject("add");
        add.put("index", index);
        add.put("alias", alias);
        if (write) {
            add.put("is_write_index", true);
        }
        return add;
    }

    private void removeAlias(ArrayNode actions, String index, String alias) {
        ObjectNode remove = actions.addObject().putObject("remove");
        remove.put("index", index);
        remove.put("alias", alias);
    }

    private String indexDefinitionJson(boolean includeAliases) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("settings")
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0);
        ObjectNode mappings = root.putObject("mappings");
        mappings.put("dynamic", "strict");
        mappings.putObject("_meta")
                .put("projection", PROJECTION_NAME)
                .put("schemaVersion", SCHEMA_VERSION)
                .put("schemaIdentity", SCHEMA_IDENTITY);
        ObjectNode fields = mappings.putObject("properties");
        keyword(fields, "listingId");
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
        if (includeAliases) {
            ObjectNode aliases = root.putObject("aliases");
            aliases.putObject(indexName());
            aliases.putObject(writeAliasName()).put("is_write_index", true);
        }
        return root.toString();
    }

    private String documentJson(ListingSearchDocument document) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("listingId", document.listingId());
        root.put("sellerType", document.sellerType());
        root.put("categoryId", document.categoryId());
        root.put("categorySlug", document.categorySlug());
        root.put("categoryName", document.categoryName());
        root.put("title", document.title());
        root.put("description", document.description());
        root.put("searchText", document.searchText());
        root.put("condition", document.condition());
        root.put("priceAmount", document.priceAmount());
        root.put("currency", document.currency());
        root.put("publicCity", document.publicCity());
        root.put("publicCityKey", document.publicCityKey());
        root.put("publicRegion", document.publicRegion());
        root.put("publicRegionKey", document.publicRegionKey());
        root.put("status", "ACTIVE");
        root.put("visibility", "PUBLIC");
        root.put("inventoryAvailable", document.inventoryAvailable());
        if (document.primaryImageUrl() != null) {
            root.put("primaryImageUrl", document.primaryImageUrl());
        }
        root.put("publishedAt", document.publishedAt().toString());
        return root.toString();
    }

    private void validateDocuments(List<ListingSearchDocument> documents) {
        if (documents == null) {
            throw new ListingSearchUnavailableException("Listing search rebuild documents are required.");
        }
        Set<String> listingIds = new HashSet<>();
        for (ListingSearchDocument document : documents) {
            validateDocument(document);
            if (!listingIds.add(document.listingId())) {
                throw new ListingSearchUnavailableException(
                        "Listing search rebuild contains duplicate documents.");
            }
        }
    }

    private void validateDocument(ListingSearchDocument document) {
        if (document == null
                || !hasText(document.listingId())
                || !Set.of("INDIVIDUAL", "BUSINESS").contains(document.sellerType())
                || !hasText(document.categoryId())
                || !hasText(document.title())
                || !hasText(document.condition())
                || document.priceAmount() == null
                || document.priceAmount().signum() < 0
                || !hasText(document.currency())
                || document.publishedAt() == null) {
            throw new ListingSearchUnavailableException(
                    "Listing search projection document is incompatible.");
        }
    }

    private String searchJson(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ObjectNode root = baseSearchJson(sellerType, criteria, limit);
        sort(root, criteria);
        searchAfter(root, criteria);
        return root.toString();
    }

    private String relevanceSearchJson(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ObjectNode root = baseSearchJson(sellerType, criteria, limit);
        ArrayNode filter = (ArrayNode) root.path("query").path("bool").path("filter");
        booleanTerm(filter, "inventoryAvailable", true);
        ArrayNode sort = root.putArray("sort");
        sort.addObject().putObject("_score").put("order", "desc");
        sort.addObject().putObject("publishedAt").put("order", "desc");
        sort.addObject().putObject("listingId").put("order", "asc");
        return root.toString();
    }

    private ObjectNode baseSearchJson(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", limit);
        root.put("_source", false);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        term(filter, "sellerType", sellerType);
        term(filter, "status", "ACTIVE");
        term(filter, "visibility", "PUBLIC");
        optionalTerm(filter, "categoryId", criteria.categoryId());
        optionalTerm(filter, "condition", criteria.condition());
        optionalTerm(filter, "publicCityKey", key(criteria.city()));
        optionalTerm(filter, "publicRegionKey", key(criteria.county()));
        priceRange(filter, criteria);
        if (hasText(criteria.keyword())) {
            ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
            multiMatch.put("query", criteria.keyword());
            multiMatch.putArray("fields")
                    .add("title^3")
                    .add("description")
                    .add("searchText^2")
                    .add("categoryName")
                    .add("categorySlug")
                    .add("condition")
                    .add("publicCity")
                    .add("publicRegion");
        }
        return root;
    }

    private List<String> responseIds(String body) {
        try {
            List<String> ids = new ArrayList<>();
            JsonNode hits = objectMapper.readTree(body).path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    String id = hit.path("_id").asText();
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch listing search returned invalid JSON.", exception);
        }
    }

    private void sort(ObjectNode root, PublicListingSearchCriteria criteria) {
        ArrayNode sort = root.putArray("sort");
        if ("price_asc".equals(criteria.sort())) {
            sort.addObject().putObject("priceAmount").put("order", "asc");
        } else if ("price_desc".equals(criteria.sort())) {
            sort.addObject().putObject("priceAmount").put("order", "desc");
        }
        sort.addObject().putObject("publishedAt").put("order", "desc");
        sort.addObject().putObject("listingId").put("order", "desc");
    }

    private void searchAfter(ObjectNode root, PublicListingSearchCriteria criteria) {
        if (!hasText(criteria.cursorListingId()) || criteria.cursorPublishedAt() == null) {
            return;
        }
        ArrayNode searchAfter = root.putArray("search_after");
        if ("price_asc".equals(criteria.sort()) || "price_desc".equals(criteria.sort())) {
            searchAfter.add(criteria.cursorPrice());
        }
        searchAfter.add(criteria.cursorPublishedAt().toString());
        searchAfter.add(criteria.cursorListingId());
    }

    private void priceRange(ArrayNode filter, PublicListingSearchCriteria criteria) {
        if (criteria.minPrice() == null && criteria.maxPrice() == null) {
            return;
        }
        ObjectNode range = filter.addObject().putObject("range").putObject("priceAmount");
        if (criteria.minPrice() != null) {
            range.put("gte", criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            range.put("lte", criteria.maxPrice());
        }
    }

    private void term(ArrayNode filter, String field, String value) {
        filter.addObject().putObject("term").put(field, value);
    }

    private void booleanTerm(ArrayNode filter, String field, boolean value) {
        filter.addObject().putObject("term").put(field, value);
    }

    private void optionalTerm(ArrayNode filter, String field, String value) {
        if (hasText(value)) {
            term(filter, field, value);
        }
    }

    private void keyword(ObjectNode fields, String field) {
        fields.putObject(field).put("type", "keyword");
    }

    private void text(ObjectNode fields, String field) {
        fields.putObject(field).put("type", "text");
    }

    private void textWithKeyword(ObjectNode fields, String field) {
        ObjectNode definition = fields.putObject(field);
        definition.put("type", "text");
        definition.putObject("fields").putObject("keyword").put("type", "keyword");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        Duration timeout = defaultDuration(properties.opensearch().requestTimeout(), Duration.ofSeconds(3));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(timeout);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType);
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ListingSearchUnavailableException("OpenSearch request was interrupted.", exception);
        }
    }

    private URI uri(String path) {
        String baseUrl = properties.opensearch().baseUrl();
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + path);
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ListingSearchUnavailableException(operation + " failed.");
        }
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String key(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private record AliasTarget(boolean exists, String target) {
        private static AliasTarget absent() {
            return new AliasTarget(false, null);
        }
    }
}
