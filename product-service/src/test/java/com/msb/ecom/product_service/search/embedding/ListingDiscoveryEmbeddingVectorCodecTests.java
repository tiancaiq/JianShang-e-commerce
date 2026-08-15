package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingDiscoveryEmbeddingVectorCodecTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void encodingIsDeterministicBigEndianAndNormalizesNegativeZero() {
        ArrayNode first = vector(0.25d);
        first.set(0, objectMapper.getNodeFactory().numberNode(-0.0d));
        first.set(1, objectMapper.getNodeFactory().numberNode(Float.MAX_VALUE));
        ArrayNode second = vector(0.25d);
        second.set(0, objectMapper.getNodeFactory().numberNode(0.0d));
        second.set(1, objectMapper.getNodeFactory().numberNode(Float.MAX_VALUE));

        var encodedFirst = ListingDiscoveryEmbeddingVectorCodec.encode(first);
        var encodedSecond = ListingDiscoveryEmbeddingVectorCodec.encode(second);

        assertThat(encodedFirst.bytes()).hasSize(6144);
        assertThat(encodedFirst.bytes()).containsExactly(encodedSecond.bytes());
        assertThat(encodedFirst.hash()).isEqualTo(encodedSecond.hash());
        ByteBuffer bytes = ByteBuffer.wrap(encodedFirst.bytes()).order(ByteOrder.BIG_ENDIAN);
        assertThat(bytes.getFloat()).isEqualTo(0.0f);
        assertThat(bytes.getFloat()).isEqualTo(Float.MAX_VALUE);
    }

    @Test
    void rejectsValuesThatOverflowFloat32() {
        ArrayNode vector = vector(0.25d);
        vector.set(0, objectMapper.getNodeFactory().numberNode(1.0e100d));

        assertThatThrownBy(() -> ListingDiscoveryEmbeddingVectorCodec.encode(vector))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesOnlyExactBigEndianFiniteReceiptBytes() {
        var encoded = ListingDiscoveryEmbeddingVectorCodec.encode(vector(0.25d));
        var decoded = ListingDiscoveryEmbeddingVectorCodec.decode(encoded.bytes());

        assertThat(decoded.values()).hasSize(1536).containsOnly(0.25f);
        assertThat(decoded.hash()).isEqualTo(encoded.hash());

        byte[] shortValue = new byte[6143];
        assertThatThrownBy(() -> ListingDiscoveryEmbeddingVectorCodec.decode(shortValue))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] nonFinite = encoded.bytes();
        ByteBuffer.wrap(nonFinite).order(ByteOrder.BIG_ENDIAN).putFloat(Float.NaN);
        assertThatThrownBy(() -> ListingDiscoveryEmbeddingVectorCodec.decode(nonFinite))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArrayNode vector(double value) {
        ArrayNode vector = objectMapper.createArrayNode();
        for (int index = 0; index < 1536; index++) {
            vector.add(value);
        }
        return vector;
    }
}
