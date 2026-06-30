package com.msb.ecom.common.web.http;

public final class IfMatchVersion {

    private IfMatchVersion() {
    }

    /**
     * Parses the required optimistic-lock version carried by an HTTP If-Match header.
     */
    public static long parseRequired(String ifMatch, String errorMessage) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
