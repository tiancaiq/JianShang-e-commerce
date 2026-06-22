package com.msb.ecom.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BusinessVerificationWebhookRequest(
        @NotBlank
        @Size(max = 128)
        String eventId,

        @NotBlank
        @Size(max = 26)
        String applicationId,

        @NotBlank
        @Size(max = 64)
        String outcome,

        @Size(max = 1000)
        String reason
) {
}
