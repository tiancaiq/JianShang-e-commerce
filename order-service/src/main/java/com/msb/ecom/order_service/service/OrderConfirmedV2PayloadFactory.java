package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.OrderConfirmationException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class OrderConfirmedV2PayloadFactory {

    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    private OrderConfirmedV2PayloadFactory() {
    }

    // Serializes the bounded v2 payload while preserving v1 as a separate unchanged contract.
    static String serialize(
            ObjectMapper objectMapper,
            String orderId,
            String persistedBuyerId,
            CheckoutAggregate checkout,
            CheckoutPaymentBinding payment,
            List<String> businessIds,
            Instant confirmedAt) {
        if (!validId(orderId)
                || !validId(persistedBuyerId)
                || checkout == null
                || payment == null
                || !validId(checkout.id())
                || !validId(payment.paymentIntentId())
                || businessIds == null
                || businessIds.isEmpty()
                || businessIds.size() > 50
                || businessIds.stream().distinct().count() != businessIds.size()
                || businessIds.stream().anyMatch(id -> !validId(id))
                || confirmedAt == null) {
            throw serializationFailure();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("checkoutId", checkout.id());
        payload.put("paymentIntentId", payment.paymentIntentId());
        payload.put("status", "CONFIRMED");
        payload.put("businessIds", List.copyOf(businessIds));
        payload.put("confirmedAt", confirmedAt.toString());
        payload.put("recipientUserId", persistedBuyerId);
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 4096) {
                throw serializationFailure();
            }
            return json;
        } catch (OrderConfirmationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw serializationFailure();
        }
    }

    private static boolean validId(String value) {
        return value != null && ULID.matcher(value).matches();
    }

    private static OrderConfirmationException serializationFailure() {
        return new OrderConfirmationException(
                "ORDER_EVENT_SERIALIZATION_FAILED",
                "Order confirmation could not be completed.");
    }
}
