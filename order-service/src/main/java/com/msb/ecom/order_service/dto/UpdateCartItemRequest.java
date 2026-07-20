package com.msb.ecom.order_service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateCartItemRequest(
        @Min(1) @Max(999) int quantity
) {
}
