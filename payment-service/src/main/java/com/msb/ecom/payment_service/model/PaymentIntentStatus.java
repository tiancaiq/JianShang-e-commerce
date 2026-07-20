package com.msb.ecom.payment_service.model;

public enum PaymentIntentStatus {
    CREATED,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(PaymentIntentStatus target) {
        return switch (this) {
            case CREATED -> target == REQUIRES_ACTION || target == PROCESSING || target == FAILED;
            case REQUIRES_ACTION ->
                    target == PROCESSING || target == SUCCEEDED || target == FAILED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
