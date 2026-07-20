package com.msb.ecom.order_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateCheckoutRequest(
        @NotNull @Min(0) Long cartVersion,
        @NotBlank
        @Pattern(regexp = "^[0-9A-HJKMNP-TV-Z]{26}$")
        String addressId
) {
}
