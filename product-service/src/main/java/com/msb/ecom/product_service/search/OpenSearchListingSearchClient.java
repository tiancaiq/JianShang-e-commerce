package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class OpenSearchListingSearchClient {

    private final ListingSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public OpenSearchListingSearchClient(ListingSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Duration connectTimeout = properties.opensearch().connectTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout)
                .build();
    }

    public boolean enabled() {
        return properties.openSearchEnabled();
    }

    public String indexName() {
        return properties.opensearch().index();
    }

    // Creates the derived listing index lazily so local dev can keep OpenSearch disabled.
    public void ensureIndex() {
        if (!enabled() || !properties.opensearch().initializeIndex() || indexReady.get()) {
            return;
        }
        HttpResponse<String> head = send("HEAD", "/" + indexName(), null);
        if (head.statusCode() == 200) {
            indexReady.set(true);
            return;
        }
        if (head.statusCode() != 404) {
            throw new ListingSearchUnavailableException("OpenSearch index check failed with HTTP " + head.statusCode() + ".");
        }

        HttpResponse<String> created = send("PUT", "/" + indexName(), mappingJson());
        if (created.statusCode() < 200 || created.statusCode() >= 300) {
            throw new ListingSearchUnavailableException("OpenSearch index creation failed with HTTP " + created.statusCode() + ".");
        }
        indexReady.set(true);
    }

    public List<String> searchIds(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ensureIndex();
        HttpResponse<String> response = send("POST", "/" + indexName() + "/_search", searchJson(sellerType, criteria, limit));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ListingSearchUnavailableException("OpenSearch listing search failed with HTTP " + response.statusCode() + ".");
        }
        try {
            List<String> ids = new ArrayList<>();
            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    ids.add(hit.path("_id").asText());
                }
            }
            return ids;
        } catch (IOException exception) {
            throw new ListingSearchUnavailableException("OpenSearch listing search returned invalid JSON.", exception);
        }
    }

    public void upsert(ListingSearchDocument document) {
        ensureIndex();
        HttpResponse<String> response = send("PUT", "/" + indexName() + "/_doc/" + document.listingId(), documentJson(document));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ListingSearchUnavailableException("OpenSearch listing upsert failed with HTTP " + response.statusCode() + ".");
        }
    }

    public void delete(String listingId) {
        ensureIndex();
        HttpResponse<String> response = send("DELETE", "/" + indexName() + "/_doc/" + listingId, null);
        if (response.statusCode() != 404 && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            throw new ListingSearchUnavailableException("OpenSearch listing delete failed with HTTP " + response.statusCode() + ".");
        }
    }

    public int rebuild(List<ListingSearchDocument> documents) {
        ensureIndex();
        int indexed = 0;
        for (ListingSearchDocument document : documents) {
            upsert(document);
            indexed++;
        }
        return indexed;
    }

    private HttpResponse<String> send(String method, String path, String body) {
        Duration requestTimeout = properties.opensearch().requestTimeout();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
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

    private String mappingJson() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode propertiesNode = root.putObject("mappings").putObject("properties");
        keyword(propertiesNode, "listingId");
        keyword(propertiesNode, "sellerType");
        keyword(propertiesNode, "categoryId");
        keyword(propertiesNode, "categorySlug");
        text(propertiesNode, "categoryName");
        text(propertiesNode, "title");
        text(propertiesNode, "description");
        keyword(propertiesNode, "condition");
        propertiesNode.putObject("priceAmount").put("type", "double");
        keyword(propertiesNode, "currency");
        keyword(propertiesNode, "publicCity");
        keyword(propertiesNode, "publicCityKey");
        keyword(propertiesNode, "publicRegion");
        keyword(propertiesNode, "publicRegionKey");
        propertiesNode.putObject("publishedAt").put("type", "date");
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
        root.put("condition", document.condition());
        root.put("priceAmount", document.priceAmount());
        root.put("currency", document.currency());
        root.put("publicCity", document.publicCity());
        root.put("publicCityKey", document.publicCityKey());
        root.put("publicRegion", document.publicRegion());
        root.put("publicRegionKey", document.publicRegionKey());
        root.put("publishedAt", document.publishedAt().toString());
        return root.toString();
    }

    private String searchJson(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", limit);
        root.put("_source", false);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        term(filter, "sellerType", sellerType);
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
                    .add("categoryName")
                    .add("categorySlug")
                    .add("condition")
                    .add("publicCity")
                    .add("publicRegion");
        }
        sort(root, criteria);
        searchAfter(root, criteria);
        return root.toString();
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

    private void optionalTerm(ArrayNode filter, String field, String value) {
        if (hasText(value)) {
            term(filter, field, value);
        }
    }

    private void keyword(ObjectNode propertiesNode, String field) {
        propertiesNode.putObject(field).put("type", "keyword");
    }

    private void text(ObjectNode propertiesNode, String field) {
        propertiesNode.putObject(field).put("type", "text");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String key(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
