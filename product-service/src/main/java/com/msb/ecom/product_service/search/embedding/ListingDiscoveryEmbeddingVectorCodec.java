package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ListingDiscoveryEmbeddingVectorCodec {

    public static final int DIMENSIONS = 1536;
    public static final int BYTE_LENGTH = DIMENSIONS * Float.BYTES;

    private ListingDiscoveryEmbeddingVectorCodec() {
    }

    // Canonicalizes JSON numbers to deterministic big-endian float32 bytes for replay checks and rebuilds.
    public static CanonicalVector encode(JsonNode vectorNode) {
        if (vectorNode == null
                || !vectorNode.isArray()
                || vectorNode.size() != DIMENSIONS) {
            throw invalid();
        }
        ByteBuffer buffer = ByteBuffer.allocate(BYTE_LENGTH).order(ByteOrder.BIG_ENDIAN);
        for (JsonNode componentNode : vectorNode) {
            if (!componentNode.isNumber()) {
                throw invalid();
            }
            double doubleValue = componentNode.doubleValue();
            float floatValue = componentNode.floatValue();
            if (!Double.isFinite(doubleValue) || !Float.isFinite(floatValue)) {
                throw invalid();
            }
            buffer.putFloat(floatValue == 0.0f ? 0.0f : floatValue);
        }
        byte[] bytes = buffer.array();
        return new CanonicalVector(bytes, sha256(bytes));
    }

    // Decodes the receipt's fixed-width representation without accepting an alternate vector encoding.
    public static DecodedVector decode(byte[] vectorBytes) {
        if (vectorBytes == null || vectorBytes.length != BYTE_LENGTH) {
            throw invalid();
        }
        ByteBuffer source = ByteBuffer.wrap(vectorBytes).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer canonical = ByteBuffer.allocate(BYTE_LENGTH).order(ByteOrder.BIG_ENDIAN);
        float[] values = new float[DIMENSIONS];
        for (int index = 0; index < DIMENSIONS; index++) {
            float value = source.getFloat();
            if (!Float.isFinite(value)) {
                throw invalid();
            }
            float normalized = value == 0.0f ? 0.0f : value;
            values[index] = normalized;
            canonical.putFloat(normalized);
        }
        byte[] canonicalBytes = canonical.array();
        return new DecodedVector(values, sha256(canonicalBytes));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Listing discovery vector hash is unavailable.", exception);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "The listing discovery embedding result is invalid.");
    }

    public record CanonicalVector(byte[] bytes, String hash) {

        public CanonicalVector {
            if (bytes == null || bytes.length != BYTE_LENGTH) {
                throw invalid();
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record DecodedVector(float[] values, String hash) {

        public DecodedVector {
            if (values == null || values.length != DIMENSIONS) {
                throw invalid();
            }
            values = values.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }
    }
}
