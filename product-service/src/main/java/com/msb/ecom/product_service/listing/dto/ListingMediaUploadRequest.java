package com.msb.ecom.product_service.listing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ListingMediaUploadRequest(
        @NotBlank
        @Size(max = 80)
        String contentType,

        @Size(max = 255)
        String fileName,

        @Min(1)
        long sizeBytes,

        @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "Checksum must be a SHA-256 hex value.")
        String checksumSha256
) {
}
