package com.msb.ecom.order_service.service;

public interface PlatformPolicyProvider {

    Policy current();

    record Policy(
            String id,
            String source,
            String version,
            String shippingText,
            String cancellationText,
            String returnText
    ) {
    }
}
