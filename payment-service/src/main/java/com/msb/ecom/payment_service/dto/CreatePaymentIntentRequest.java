package com.msb.ecom.payment_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreatePaymentIntentRequest(
        @NotBlank @Pattern(regexp = "[0-7][0-9A-HJKMNP-TV-Z]{25}") String checkoutId,
        @PositiveOrZero long checkoutVersion,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String checkoutSnapshotHash,
        @NotBlank @Pattern(regexp = "[0-7][0-9A-HJKMNP-TV-Z]{25}") String buyerId,
        @NotEmpty @Size(max = 50)
        // Business IDs are opaque platform IDs; legacy deterministic fixtures are not canonical ULIDs.
        List<@Pattern(regexp = "[0-9A-Z]{26}") String> businessIds,
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "USD") String currency,
        @NotNull @Future Instant expiresAt
) {
    public CreatePaymentIntentRequest {
        businessIds = businessIds == null ? null : List.copyOf(businessIds);
    }
}
