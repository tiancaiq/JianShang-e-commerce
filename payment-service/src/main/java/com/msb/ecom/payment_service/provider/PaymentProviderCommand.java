package com.msb.ecom.payment_service.provider;

import java.math.BigDecimal;
import java.util.List;

public record PaymentProviderCommand(
        String paymentIntentId,
        String idempotencyKey,
        String checkoutId,
        BigDecimal amount,
        String currency,
        String paymentMethodType,
        String captureMethod,
        String merchantOfRecord,
        String fundsFlow,
        List<String> businessIds
) {
    public PaymentProviderCommand {
        businessIds = List.copyOf(businessIds);
    }
}
