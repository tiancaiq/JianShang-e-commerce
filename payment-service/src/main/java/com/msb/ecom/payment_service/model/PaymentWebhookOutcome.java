package com.msb.ecom.payment_service.model;

public enum PaymentWebhookOutcome {
    APPLIED,
    UNKNOWN_INTENT,
    IGNORED_LATE_EVENT,
    REJECTED_ILLEGAL_TRANSITION
}
