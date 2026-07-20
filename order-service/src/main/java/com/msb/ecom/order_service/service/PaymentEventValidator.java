package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PaymentEventValidator {

    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern CORRELATION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern PROVIDER_EVENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<String> EVENT_TYPES =
            Set.of("payment.succeeded", "payment.failed");

    // Rejects every envelope that is not the exact bounded payment v1 contract.
    public void validate(PaymentEventEnvelope event) {
        boolean valid = event != null
                && ULID.matcher(value(event.eventId())).matches()
                && EVENT_TYPES.contains(event.eventType())
                && event.schemaVersion() == 1
                && event.occurredAt() != null
                && CORRELATION.matcher(value(event.correlationId())).matches()
                && ULID.matcher(value(event.paymentIntentId())).matches()
                && ULID.matcher(value(event.checkoutId())).matches()
                && event.paymentIntentId().equals(event.partitionKey())
                && event.payload() != null
                && PROVIDER_EVENT.matcher(value(event.payload().providerEventId())).matches()
                && "USD".equals(event.payload().currency())
                && validAmount(event.payload().amount())
                && expectedStatus(event.eventType()).equals(event.payload().status());
        if (!valid) {
            throw new OrderConfirmationException(
                    "PAYMENT_EVENT_INVALID",
                    "Payment event envelope is invalid.");
        }
    }

    private String expectedStatus(String eventType) {
        return "payment.succeeded".equals(eventType) ? "SUCCEEDED" : "FAILED";
    }

    private boolean validAmount(BigDecimal amount) {
        return amount != null
                && amount.compareTo(BigDecimal.ZERO) > 0
                && amount.scale() >= 0
                && amount.scale() <= 4
                && amount.precision() <= 19;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
