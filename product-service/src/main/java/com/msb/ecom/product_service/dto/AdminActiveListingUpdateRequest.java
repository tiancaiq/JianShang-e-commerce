package com.msb.ecom.product_service.dto;

import com.msb.ecom.product_service.model.ListingCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminActiveListingUpdateRequest(
        @NotBlank @Size(max = 26) String categoryId,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull ListingCondition condition,
        @Size(max = 1000) String conditionNotes,
        @NotNull @Valid MoneyRequest price,
        Boolean negotiable,
        @Valid ListingLocationRequest location,
        @Size(max = 64) String sku,
        @PositiveOrZero Integer quantity,
        @NotBlank @Size(max = 1000) String reason
) {
}
