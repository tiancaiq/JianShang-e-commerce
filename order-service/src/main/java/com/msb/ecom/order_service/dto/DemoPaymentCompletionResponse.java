package com.msb.ecom.order_service.dto;

public record DemoPaymentCompletionResponse(
        String paymentIntentId,
        String status,
        String outcome,
        boolean replayed
) {
}
