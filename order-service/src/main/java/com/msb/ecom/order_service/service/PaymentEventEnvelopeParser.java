package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventEnvelopeParser {

    private static final int MAX_ENVELOPE_BYTES = 16 * 1024;
    private final ObjectMapper objectMapper;

    public PaymentEventEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        this.objectMapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    // Parses only the bounded exact v1 envelope before domain validation.
    public PaymentEventEnvelope parse(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_ENVELOPE_BYTES) {
            throw invalid();
        }
        try {
            return objectMapper.readValue(payload, PaymentEventEnvelope.class);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private OrderConfirmationException invalid() {
        return new OrderConfirmationException(
                "PAYMENT_EVENT_INVALID",
                "Payment event envelope is invalid.");
    }
}
