package com.msb.ecom.common.core.validation;

import java.util.regex.Pattern;

/**
 * Shared string normalization helpers for request and configuration text.
 */
public final class TextInputs {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextInputs() {
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static String collapseWhitespaceToEmpty(String value) {
        return collapse(value);
    }

    public static String collapseWhitespaceToNull(String value) {
        String normalized = collapse(value);
        return normalized.isBlank() ? null : normalized;
    }

    public static String requireCollapsed(String fieldName, String value, int maxLength) {
        String normalized = collapseWhitespaceToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return normalized;
    }

    private static String collapse(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value.trim()).replaceAll(" ");
    }
}
