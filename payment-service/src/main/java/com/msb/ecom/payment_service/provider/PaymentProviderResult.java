package com.msb.ecom.payment_service.provider;

import com.msb.ecom.payment_service.model.PaymentIntentStatus;

public record PaymentProviderResult(
        PaymentIntentStatus status,
        String providerReference,
        String actionType,
        String safeErrorCode,
        String safeErrorMessage
) {
}
