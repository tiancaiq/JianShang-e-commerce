package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationCursorCodecTests {

    @Test
    void roundTripsBothStableSortValues() {
        var row = new NotificationReadRow(
                id(2), "ORDER_CONFIRMED", "ORDER_CONFIRMED_V1",
                "{\"orderId\":\"" + id(1) + "\"}", "/account", null,
                Instant.parse("2026-07-20T12:00:00Z"));

        var cursor = NotificationCursorCodec.decode(NotificationCursorCodec.encode(row));

        assertThat(cursor.createdAt()).isEqualTo(row.createdAt());
        assertThat(cursor.notificationId()).isEqualTo(row.id());
    }

    @Test
    void rejectsMalformedCursorAndUnboundedLimit() {
        assertThatThrownBy(() -> NotificationCursorCodec.decode("not+url-safe"))
                .isInstanceOf(NotificationReadException.class)
                .extracting(exception -> ((NotificationReadException) exception).code())
                .isEqualTo("NOTIFICATION_CURSOR_INVALID");
        assertThatThrownBy(() -> NotificationCursorCodec.limit("51"))
                .isInstanceOf(NotificationReadException.class)
                .extracting(exception -> ((NotificationReadException) exception).code())
                .isEqualTo("NOTIFICATION_LIMIT_INVALID");
    }

    public static String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }
}
