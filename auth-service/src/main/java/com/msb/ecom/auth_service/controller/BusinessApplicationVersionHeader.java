package com.msb.ecom.auth_service.controller;

final class BusinessApplicationVersionHeader {

    private static final String ERROR_MESSAGE = "If-Match must contain the current business application version";

    private BusinessApplicationVersionHeader() {
    }

    // Parses the business application aggregate version used by optimistic-locking commands.
    static Long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException(ERROR_MESSAGE);
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(ERROR_MESSAGE);
        }
    }
}
