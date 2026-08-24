package com.msb.ecom.product_service.dto;

import java.util.List;

public record CategoryAttributeResponse(
        String id,
        String key,
        String label,
        String description,
        String dataType,
        boolean required,
        boolean searchable,
        boolean filterable,
        String allowedValuesJson,
        String validationJson,
        int displayOrder,
        String status,
        long version,
        List<CategoryAttributeOptionResponse> options
) {
}
