package com.msb.ecom.product_service.dto;

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
