package com.msb.ecom.product_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminListingRemoveRequest(
        @NotBlank @Size(max = 1000) String reason
) {
}
