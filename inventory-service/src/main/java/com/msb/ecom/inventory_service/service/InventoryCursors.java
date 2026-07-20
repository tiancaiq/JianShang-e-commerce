package com.msb.ecom.inventory_service.service;

import com.msb.ecom.inventory_service.repository.InventoryMovementRecord;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

final class InventoryCursors {

    private InventoryCursors() {
    }

    static MovementCursor decodeMovementCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 512) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\t", -1);
            if (parts.length != 2 || parts[1].length() != 26) {
                throw new IllegalArgumentException("Cursor is invalid.");
            }
            return new MovementCursor(Instant.parse(parts[0]), parts[1]);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
    }

    static String encodeMovementCursor(InventoryMovementRecord movement) {
        String raw = movement.createdAt() + "\t" + movement.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    record MovementCursor(Instant createdAt, String id) {
    }
}
