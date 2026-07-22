package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static com.msb.ecom.notification_service.service.NotificationCursorCodecTests.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPresentationMapperTests {

    private final NotificationPresentationMapper mapper =
            new NotificationPresentationMapper(new ObjectMapper());

    @Test
    void exposesOnlyAllowlistedPresentationFields() {
        var item = mapper.map(row("{\"orderId\":\"" + id(1) + "\"}"));

        assertThat(item.id()).isEqualTo(id(2));
        assertThat(item.presentationArgs()).isEqualTo(Map.of("orderId", id(1)));
        assertThat(item.safeRoute()).isEqualTo("/account");
        assertThat(item.read()).isFalse();
    }

    @Test
    void corruptOrExpandedArgsFailClosed() {
        assertThatThrownBy(() -> mapper.map(row(
                "{\"orderId\":\"" + id(1) + "\",\"email\":\"hidden@example.invalid\"}")))
                .isInstanceOf(NotificationReadException.class)
                .extracting(exception -> ((NotificationReadException) exception).code())
                .isEqualTo("NOTIFICATION_DATA_UNAVAILABLE");
    }

    private NotificationReadRow row(String args) {
        return new NotificationReadRow(
                id(2),
                "ORDER_CONFIRMED",
                "ORDER_CONFIRMED_V1",
                args,
                "/account",
                null,
                Instant.parse("2026-07-20T12:00:00Z"));
    }
}
