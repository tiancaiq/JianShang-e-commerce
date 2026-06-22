package com.msb.ecom.product_service.listing.dto;

public record CategoryAttributeResponse(
        String id,
        String key,
        String label,
        String dataType,
        boolean required,
        String allowedValuesJson,
        String validationJson,
        int displayOrder
) {
}
