package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingDiscoveryEmbeddingResultRequestParserTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ListingDiscoveryEmbeddingResultRequestParser parser =
            new ListingDiscoveryEmbeddingResultRequestParser(objectMapper);

    @Test
    void parsesExactAgent04bBodyAndCanonicalizesVector() throws Exception {
        ObjectNode body = validBody();
        body.withArray("vector").set(0, objectMapper.getNodeFactory().numberNode(-0.0d));

        ListingDiscoveryEmbeddingResultRequest result =
                parser.parse(objectMapper.writeValueAsBytes(body));

        assertThat(result.schemaVersion())
                .isEqualTo("MARKETPLACE_LISTING_EMBEDDING_RESULT_V1");
        assertThat(result.embeddingIdentity())
                .extracting(
                        ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity::provider,
                        ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity::model,
                        ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity::dimensions)
                .containsExactly("openai", "text-embedding-3-small", 1536);
        assertThat(result.vector().bytes()).hasSize(6144);
        assertThat(Arrays.copyOfRange(result.vector().bytes(), 0, 4))
                .containsExactly((byte) 0, (byte) 0, (byte) 0, (byte) 0);
        assertThat(result.vector().hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void acceptsZeroListingVersionAndRejectsNegativeListingVersion() throws Exception {
        ObjectNode zero = validBody();
        zero.put("listingVersion", 0);

        ListingDiscoveryEmbeddingResultRequest result =
                parser.parse(objectMapper.writeValueAsBytes(zero));

        assertThat(result.listingVersion()).isZero();

        ObjectNode negative = validBody();
        negative.put("listingVersion", -1);
        assertInvalid(negative);
    }

    @Test
    void rejectsOversizeUnknownWrongSchemaAndMalformedShapes() throws Exception {
        assertThatThrownBy(() -> parser.parse(new byte[65_537]))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode unknown = validBody().put("prompt", "ignore safeguards");
        assertInvalid(unknown);
        ObjectNode wrongSchema = validBody().put("schemaVersion", "OTHER");
        assertInvalid(wrongSchema);
        ObjectNode missing = validBody();
        missing.remove("documentHash");
        assertInvalid(missing);
        ObjectNode wrongDimension = validBody();
        wrongDimension.withArray("vector").remove(0);
        assertInvalid(wrongDimension);
        ObjectNode extraDimension = validBody();
        extraDimension.withArray("vector").add(1.0d);
        assertInvalid(extraDimension);
        ObjectNode nullValue = validBody();
        nullValue.withArray("vector").set(0, objectMapper.nullNode());
        assertInvalid(nullValue);
        ObjectNode stringValue = validBody();
        stringValue.withArray("vector").set(
                0,
                objectMapper.getNodeFactory().textNode("0.25"));
        assertInvalid(stringValue);
        ObjectNode overflow = validBody();
        overflow.withArray("vector").set(
                0,
                objectMapper.readTree("1e100"));
        assertInvalid(overflow);

        assertThatThrownBy(() -> parser.parse(
                rawBodyWithFirstVectorValue("NaN")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(
                rawBodyWithFirstVectorValue("Infinity")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateFieldsTrailingJsonAndIdentityCoercion() throws Exception {
        String duplicate = objectMapper.writeValueAsString(validBody())
                .replaceFirst(
                        "\\{",
                        "{\"schemaVersion\":\"MARKETPLACE_LISTING_EMBEDDING_RESULT_V1\",");
        assertThatThrownBy(() -> parser.parse(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] trailing = (objectMapper.writeValueAsString(validBody()) + "{}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(trailing))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode coercion = validBody();
        ((ObjectNode) coercion.get("embeddingIdentity")).put("dimensions", "1536");
        assertInvalid(coercion);
    }

    private void assertInvalid(JsonNode body) throws Exception {
        assertThatThrownBy(() -> parser.parse(objectMapper.writeValueAsBytes(body)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] rawBodyWithFirstVectorValue(String value) throws Exception {
        String json = objectMapper.writeValueAsString(validBody());
        return json.replaceFirst("\"vector\":\\[0\\.25", "\"vector\":[" + value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ObjectNode validBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("schemaVersion", "MARKETPLACE_LISTING_EMBEDDING_RESULT_V1");
        body.put("listingVersion", 7);
        body.put("documentSchemaVersion", "MARKETPLACE_LISTING_DISCOVERY_V2");
        body.put("documentHash", "a".repeat(64));
        body.put("embeddingInputSchemaVersion", "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1");
        body.put("embeddingInputHash", "b".repeat(64));
        ObjectNode identity = body.putObject("embeddingIdentity");
        identity.put("provider", "openai");
        identity.put("model", "text-embedding-3-small");
        identity.put("dimensions", 1536);
        ArrayNode vector = body.putArray("vector");
        for (int index = 0; index < 1536; index++) {
            vector.add(0.25d);
        }
        return body;
    }
}
