package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

final class NotificationCursorCodec {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final String VERSION = "v1";
    private static final Pattern ENCODED = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    private NotificationCursorCodec() {
    }

    // Binds both stable descending sort values and rejects noncanonical cursors.
    static Cursor decode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_CURSOR_LENGTH || !ENCODED.matcher(value).matches()) {
            throw invalidCursor();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\t", -1);
            if (parts.length != 3
                    || !VERSION.equals(parts[0])
                    || !ULID.matcher(parts[2]).matches()) {
                throw invalidCursor();
            }
            return new Cursor(Instant.parse(parts[1]), parts[2]);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    // Encodes only the final persisted row position returned by the server.
    static String encode(NotificationReadRow row) {
        String raw = VERSION + "\t" + row.createdAt() + "\t" + row.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1 && parsed <= MAX_LIMIT) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Converted to the stable bounded API error below.
        }
        throw new NotificationReadException(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_LIMIT_INVALID",
                "Limit must be from 1 through 50.");
    }

    private static NotificationReadException invalidCursor() {
        return new NotificationReadException(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_CURSOR_INVALID",
                "Notification cursor is invalid.");
    }

    record Cursor(Instant createdAt, String notificationId) {
    }
}
