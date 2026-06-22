package com.msb.ecom.product_service.listing.dto;

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
