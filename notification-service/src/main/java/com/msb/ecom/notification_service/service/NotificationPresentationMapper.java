package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.dto.NotificationPageResponse;
import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class NotificationPresentationMapper {

    private static final int MAX_ARGS_BYTES = 2048;
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Set<String> ARG_FIELDS = Set.of("orderId");

    private final ObjectMapper objectMapper;

    public NotificationPresentationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // Converts only an allowlisted persisted notification into the public read projection.
    public NotificationPageResponse.NotificationItem map(NotificationReadRow row) {
        if (row == null
                || row.id() == null
                || !ULID.matcher(row.id()).matches()
                || !"ORDER_CONFIRMED".equals(row.type())
                || !"ORDER_CONFIRMED_V1".equals(row.messageKey())
                || !"/account".equals(row.route())
                || row.createdAt() == null
                || row.messageArgsJson() == null
                || row.messageArgsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_ARGS_BYTES) {
            throw corrupt();
        }
        try {
            JsonNode args = objectMapper.readTree(row.messageArgsJson());
            if (!args.isObject()) {
                throw corrupt();
            }
            Set<String> actual = new HashSet<>();
            args.fieldNames().forEachRemaining(actual::add);
            JsonNode orderId = args.get("orderId");
            if (!actual.equals(ARG_FIELDS)
                    || orderId == null
                    || !orderId.isTextual()
                    || !ULID.matcher(orderId.textValue()).matches()) {
                throw corrupt();
            }
            return new NotificationPageResponse.NotificationItem(
                    row.id(),
                    row.type(),
                    row.messageKey(),
                    Map.of("orderId", orderId.textValue()),
                    row.route(),
                    row.readAt() != null,
                    row.readAt(),
                    row.createdAt());
        } catch (JsonProcessingException exception) {
            throw corrupt();
        }
    }

    private NotificationReadException corrupt() {
        return new NotificationReadException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NOTIFICATION_DATA_UNAVAILABLE",
                "Notification data is temporarily unavailable.");
    }
}
