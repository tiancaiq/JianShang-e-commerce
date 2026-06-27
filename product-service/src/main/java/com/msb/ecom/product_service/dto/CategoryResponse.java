package com.msb.ecom.product_service.dto;

import java.util.List;

public record CategoryResponse(
        String id,
        String slug,
        String name,
        String parentId,
        int displayOrder,
        List<CategoryAttributeResponse> attributes
) {
}
