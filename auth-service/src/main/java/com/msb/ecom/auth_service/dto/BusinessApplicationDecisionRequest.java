package com.msb.ecom.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BusinessApplicationDecisionRequest(
        @NotBlank
        @Size(max = 32)
        String decision,

        @NotBlank
        @Size(max = 1000)
        String reason
) {
}
