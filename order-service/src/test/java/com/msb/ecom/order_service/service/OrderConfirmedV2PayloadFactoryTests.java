package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedV2PayloadFactoryTests {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");

    @Test
    void serializesOnlyBoundedOrderFactsAndPersistedRecipient() throws Exception {
        String json = OrderConfirmedV2PayloadFactory.serialize(
                JSON,
                id(1),
                id(2),
                checkout(),
                payment(),
                List.of(id(100)),
                NOW);

        JsonNode payload = JSON.readTree(json);
        List<String> fields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(
                "orderId",
                "checkoutId",
                "paymentIntentId",
                "status",
                "businessIds",
                "confirmedAt",
                "recipientUserId");
        assertThat(payload.path("recipientUserId").asText()).isEqualTo(id(2));
        assertThat(json).doesNotContain(
                "email", "address", "phone", "provider", "Buyer Name", "1 Main St");
    }

    @Test
    void rejectsInvalidRecipientAndBusinessBounds() {
        assertThatThrownBy(() -> OrderConfirmedV2PayloadFactory.serialize(
                JSON, id(1), "not-a-user", checkout(), payment(), List.of(id(100)), NOW))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("ORDER_EVENT_SERIALIZATION_FAILED"));

        assertThatThrownBy(() -> OrderConfirmedV2PayloadFactory.serialize(
                JSON, id(1), id(2), checkout(), payment(), List.of(), NOW))
                .isInstanceOf(OrderConfirmationException.class);
    }

    private CheckoutAggregate checkout() {
        return new CheckoutAggregate(
                id(10), id(2), null, 1, 1, "a".repeat(64), "USD",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, NOW.plusSeconds(900), null, null, null, null,
                null, NOW, NOW, null, List.of(), List.of(), null, List.of());
    }

    private CheckoutPaymentBinding payment() {
        return new CheckoutPaymentBinding(
                id(20), id(10), 1, "a".repeat(64), id(2), List.of(id(100)),
                BigDecimal.ONE, "USD", NOW.plusSeconds(900), NOW);
    }

    private static String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }
}
