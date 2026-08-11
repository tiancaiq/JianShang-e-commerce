package com.msb.ecom.payment_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PaymentRefundProperties(boolean enabled) {
    public PaymentRefundProperties(@Value("${payment.refunds.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }
}
