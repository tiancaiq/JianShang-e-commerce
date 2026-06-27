package com.msb.ecom.product_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ListingMediaConfirmRequest(
        @Min(1)
        long sizeBytes,

        @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "Checksum must be a SHA-256 hex value.")
        String checksumSha256
) {
}
