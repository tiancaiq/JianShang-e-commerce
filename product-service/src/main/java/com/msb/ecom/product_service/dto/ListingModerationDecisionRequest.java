package com.msb.ecom.product_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ListingModerationDecisionRequest(
        @NotBlank
        @Size(max = 32)
        String decision,

        @NotBlank
        @Size(max = 1000)
        String reason
) {
}
