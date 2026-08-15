package com.msb.ecom.payment_service.dto;

public record CreatePaymentRefundRequest(String orderId, String cancellationRequestId) {
}
