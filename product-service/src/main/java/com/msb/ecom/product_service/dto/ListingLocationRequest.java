package com.msb.ecom.product_service.dto;

import jakarta.validation.constraints.Size;

public record ListingLocationRequest(
        @Size(max = 120) String city,
        @Size(max = 120) String region
) {
}
