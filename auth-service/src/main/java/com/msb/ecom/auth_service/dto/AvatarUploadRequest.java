package com.msb.ecom.auth_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AvatarUploadRequest(
        @NotBlank
        @Size(max = 80)
        String contentType,

        @Size(max = 255)
        String fileName,

        @Min(1)
        long sizeBytes
) {
}
