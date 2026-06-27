package com.msb.ecom.product_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ListingImageRequest(
        @NotBlank
        @Size(min = 26, max = 26)
        String mediaId,

        @Size(max = 250)
        String altText
) {
}
