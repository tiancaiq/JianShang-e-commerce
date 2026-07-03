package com.msb.ecom.auth_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AvatarUploadConfirmRequest(
        @NotBlank
        @Size(max = 512)
        String objectKey,

        @NotBlank
        @Size(max = 80)
        String contentType,

        @Min(1)
        long sizeBytes
) {
}
