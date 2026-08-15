package com.msb.ecom.inventory_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record InventoryCancellationRestockProperties(boolean enabled) {

    public InventoryCancellationRestockProperties(
            @Value("${inventory.cancellation-restock.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }
}
