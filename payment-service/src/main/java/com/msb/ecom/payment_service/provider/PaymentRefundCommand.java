package com.msb.ecom.payment_service.provider;

import java.math.BigDecimal;

public record PaymentRefundCommand(
        String refundId,
        String paymentIntentId,
        String providerPaymentReference,
        String orderId,
        String cancellationRequestId,
        BigDecimal amount,
        String currency
) {
}
