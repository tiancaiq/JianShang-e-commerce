package com.msb.ecom.payment_service.model;

public enum PaymentWebhookOutcome {
    APPLIED,
    UNKNOWN_INTENT,
    UNKNOWN_REFERENCE,
    IGNORED_LATE_EVENT,
    IGNORED_UNSUPPORTED_EVENT,
    REJECTED_ILLEGAL_TRANSITION
}
