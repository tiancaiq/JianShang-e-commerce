package com.msb.ecom.payment_service.dto;

public record PaymentWebhookResponse(
        String eventId,
        String paymentIntentId,
        String status,
        String outcome,
        boolean replayed
) {
}
