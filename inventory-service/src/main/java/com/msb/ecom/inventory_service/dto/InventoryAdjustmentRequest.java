package com.msb.ecom.inventory_service.dto;

import com.msb.ecom.inventory_service.model.InventoryOperation;
import com.msb.ecom.inventory_service.model.InventoryReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InventoryAdjustmentRequest(
        @NotNull InventoryOperation operation,
        @NotNull Integer quantity,
        @NotNull InventoryReason reason,
        @Size(max = 500) String note
) {
}
