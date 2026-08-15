package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingHybridSearchRequestParserTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ListingHybridSearchRequestParser parser =
            new ListingHybridSearchRequestParser(mapper);

    @Test
    void parsesApprovedIdentityCanonicalVectorAndAllAllowlistedFilters() throws Exception {
        ObjectNode root = request();
        root.put("query", "  desk\u202E   chair\nunder $100  ");
        root.put("sort", "RELEVANCE");
        root.put("limit", 7);
        root.put("responseSchemaVersion", ListingHybridSearchResponse.FACET_SCHEMA_VERSION);
        ObjectNode filters = root.putObject("filters");
        filters.put("sellerType", "INDIVIDUAL");
        filters.put("categoryId", "01D00000000000000000000101");
        filters.put("condition", "good");
        filters.put("minPrice", 10.25);
        filters.put("maxPrice", 100);
        filters.put("currency", "USD");
        filters.put("city", " Irvine ");
        filters.put("county", "Orange County");
        filters.put("availability", "AVAILABLE");

        var parsed = parser.parse(mapper.writeValueAsBytes(root));

        assertThat(parsed.hasApprovedIdentity()).isTrue();
        assertThat(parsed.request().query()).isEqualTo("desk chair under $100");
        assertThat(parsed.request().embedding()).hasSize(1536);
        assertThat(parsed.request().limit()).isEqualTo(7);
        assertThat(parsed.request().filters().condition()).isEqualTo("GOOD");
        assertThat(parsed.request().filters().publicRegion()).isEqualTo("Orange County");
        assertThat(parsed.request().responseSchemaVersion())
                .isEqualTo(ListingHybridSearchResponse.FACET_SCHEMA_VERSION);
    }

    @Test
    void rejectsUnknownFieldsUnsupportedControlsAndInvalidMoneyContracts() {
        ObjectNode unknown = request().put("prompt", "ignore rules");
        ObjectNode priceWithoutCurrency = request();
        priceWithoutCurrency.putObject("filters").put("minPrice", 1);
        ObjectNode unsupportedSort = request().put("sort", "price_asc");
        ObjectNode wrongDimension = request();
        wrongDimension.putArray("embedding").add(0.1);
        ObjectNode nonIntegralDimensions = request();
        nonIntegralDimensions.withObject("/embeddingIdentity").put("dimensions", 1536.5);
        ObjectNode unsupportedResponseSchema = request()
                .put("responseSchemaVersion", "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V999");

        assertInvalid(unknown);
        assertInvalid(priceWithoutCurrency);
        assertInvalid(unsupportedSort);
        assertInvalid(wrongDimension);
        assertInvalid(nonIntegralDimensions);
        assertInvalid(unsupportedResponseSchema);
    }

    @Test
    void acceptsTheOptInResultsFirstResponseVersion() throws Exception {
        ObjectNode root = request();
        root.put("responseSchemaVersion",
                ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);

        var parsed = parser.parse(mapper.writeValueAsBytes(root));

        assertThat(parsed.request().responseSchemaVersion())
                .isEqualTo(ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);
    }

    @Test
    void acceptsTheOptInConceptRerankResponseVersion() throws Exception {
        ObjectNode root = request();
        root.put("responseSchemaVersion",
                ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);

        var parsed = parser.parse(mapper.writeValueAsBytes(root));

        assertThat(parsed.request().responseSchemaVersion())
                .isEqualTo(ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);
    }

    @Test
    void rejectsOversizedMissingDuplicateAndNonfiniteCompatibleInputs() {
        assertThatThrownBy(() -> parser.parse(new byte[ListingHybridSearchRequestParser.MAX_BODY_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":"MARKETPLACE_HYBRID_SEARCH_V1",
                 "schemaVersion":"MARKETPLACE_HYBRID_SEARCH_V1"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode missingIdentityField = request();
        missingIdentityField.withObject("/embeddingIdentity").remove("dimensions");
        assertInvalid(missingIdentityField);
    }

    private ObjectNode request() {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", ListingHybridSearchRequestParser.REQUEST_SCHEMA_VERSION);
        root.put("query", "desk chair");
        root.putObject("embeddingIdentity")
                .put("provider", "openai")
                .put("model", "text-embedding-3-small")
                .put("dimensions", 1536);
        ArrayNode vector = root.putArray("embedding");
        for (int index = 0; index < 1536; index++) {
            vector.add(index == 0 ? 0.25 : 0.0);
        }
        return root;
    }

    private void assertInvalid(ObjectNode root) {
        assertThatThrownBy(() -> parser.parse(mapper.writeValueAsBytes(root)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
