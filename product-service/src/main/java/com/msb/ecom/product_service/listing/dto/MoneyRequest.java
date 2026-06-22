package com.msb.ecom.product_service.listing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record MoneyRequest(
        @NotNull @DecimalMin("0.00") BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency
) {
}
