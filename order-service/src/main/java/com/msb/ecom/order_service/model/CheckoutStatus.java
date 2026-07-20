package com.msb.ecom.order_service.model;

public enum CheckoutStatus {
    RESERVING,
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    PAYMENT_REVIEW,
    REFUND_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}
