package com.msb.ecom.common.core.validation;

/**
 * Shared normalization for string IDs whose database shape is a fixed-length token.
 */
public final class FixedLengthIds {

    private FixedLengthIds() {
    }

    public static String requireTrimmed(String fieldName, String value, int length) {
        String normalized = TextInputs.trimToEmpty(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        if (normalized.length() != length) {
            throw new IllegalArgumentException(fieldName + " is invalid.");
        }
        return normalized;
    }
}
