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
    private static final Set<String> TYPES = Set.of(
            "BUYER_ORDER_CONFIRMED", "BUYER_ORDER_CANCELLED", "BUYER_REFUND_COMPLETED",
            "BUYER_ORDER_ACCEPTED", "BUYER_ORDER_PROCESSING", "BUYER_ORDER_SHIPPED",
            "BUYER_ORDER_DELIVERED", "SELLER_NEW_ORDER", "SELLER_ORDER_CANCELLED",
            "BUYER_RETURN_AUTHORIZED", "BUYER_RETURN_RECEIVED",
            "BUYER_RETURN_REFUND_COMPLETED", "SELLER_RETURN_REQUESTED",
            "ORDER_CONFIRMED");

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
                || !TYPES.contains(row.type())
                || !(row.type() + "_V1").equals(row.messageKey())
                    && !("ORDER_CONFIRMED".equals(row.type())
                        && "ORDER_CONFIRMED_V1".equals(row.messageKey()))
                || !safeRoute(row.route())
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
            Set<String> allowed = Set.of("orderId", "businessOrderId", "storeDisplayName");
            if (!allowed.containsAll(actual) || !actual.contains("orderId")
                    || orderId == null
                    || !orderId.isTextual()
                    || !ULID.matcher(orderId.textValue()).matches()) {
                throw corrupt();
            }
            java.util.LinkedHashMap<String, String> projection = new java.util.LinkedHashMap<>();
            projection.put("orderId", orderId.textValue());
            JsonNode businessOrderId = args.get("businessOrderId");
            if (businessOrderId != null) {
                if (!businessOrderId.isTextual() || !ULID.matcher(businessOrderId.textValue()).matches()) {
                    throw corrupt();
                }
                projection.put("businessOrderId", businessOrderId.textValue());
            }
            JsonNode store = args.get("storeDisplayName");
            if (store != null) {
                if (!store.isTextual() || store.textValue().isBlank() || store.textValue().length() > 120) {
                    throw corrupt();
                }
                projection.put("storeDisplayName", store.textValue());
            }
            return new NotificationPageResponse.NotificationItem(
                    row.id(),
                    row.type(),
                    row.messageKey(),
                    projection,
                    row.route(),
                    row.readAt() != null,
                    row.readAt(),
                    row.createdAt());
        } catch (JsonProcessingException exception) {
            throw corrupt();
        }
    }

    private boolean safeRoute(String route) {
        return "/account".equals(route)
                || (route != null && route.matches("/account/orders/[0-7][0-9A-HJKMNP-TV-Z]{25}"))
                || (route != null && route.matches("/seller/orders/[0-7][0-9A-HJKMNP-TV-Z]{25}"));
    }

    private NotificationReadException corrupt() {
        return new NotificationReadException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NOTIFICATION_DATA_UNAVAILABLE",
                "Notification data is temporarily unavailable.");
    }
}
