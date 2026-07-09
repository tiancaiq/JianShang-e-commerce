package com.msb.ecom.auth_service.controller;

final class BusinessStoreVersionHeader {

    private static final String ERROR_MESSAGE = "If-Match must contain the current business store version";

    private BusinessStoreVersionHeader() {
    }

    // Parses the store profile version used by BUS-05 optimistic-locking updates.
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
