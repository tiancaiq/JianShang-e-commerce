package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.model.BusinessOrderView;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

final class BusinessOrderCursorCodec {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final String VERSION = "v1";
    private static final Pattern ENCODED = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private BusinessOrderCursorCodec() {
    }

    // Decodes both persisted queue sort values from a bounded opaque cursor.
    static Cursor decode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_CURSOR_LENGTH || !ENCODED.matcher(value).matches()) {
            throw invalidCursor();
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8);
            String[] parts = raw.split("\t", -1);
            if (parts.length != 3
                    || !VERSION.equals(parts[0])
                    || !ULID.matcher(parts[2]).matches()) {
                throw invalidCursor();
            }
            return new Cursor(Instant.parse(parts[1]), parts[2]);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    static String encode(BusinessOrderView order) {
        String raw =
                VERSION + "\t" + order.createdAt() + "\t" + order.businessOrderId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidLimit();
        }
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw invalidLimit();
        }
        return parsed;
    }

    private static BusinessOrderException invalidLimit() {
        return new BusinessOrderException(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_ORDER_LIMIT_INVALID",
                "Limit must be from 1 through 50.");
    }

    private static BusinessOrderException invalidCursor() {
        return new BusinessOrderException(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_ORDER_CURSOR_INVALID",
                "Business order cursor is invalid.");
    }

    record Cursor(Instant createdAt, String businessOrderId) {
    }
}
