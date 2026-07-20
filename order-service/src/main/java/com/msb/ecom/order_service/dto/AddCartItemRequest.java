package com.msb.ecom.order_service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCartItemRequest(
        @NotBlank @Size(min = 26, max = 26) String listingId,
        @Min(1) @Max(999) int quantity
) {
}
