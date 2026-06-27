package com.msb.ecom.product_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateListingImagesRequest(
        @NotNull
        @Size(max = 12)
        List<@Valid ListingImageRequest> images
) {
}
