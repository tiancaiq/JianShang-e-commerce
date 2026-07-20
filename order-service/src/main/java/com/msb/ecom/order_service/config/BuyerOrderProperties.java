package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BuyerOrderProperties {

    private final boolean enabled;

    public BuyerOrderProperties(
            @Value("${order.buyer-views.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
