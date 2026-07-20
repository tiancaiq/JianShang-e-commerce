package com.msb.ecom.inventory_service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record InventoryInitializeRequest(
        @NotNull @PositiveOrZero Integer onHand,
        @Size(max = 500) String note
) {
}
