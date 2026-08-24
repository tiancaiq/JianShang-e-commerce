package com.msb.ecom.product_service.dto;

import java.util.List;

public record CategoryResponse(
        String id,
        String slug,
        String name,
        String parentId,
        int displayOrder,
        String status,
        String sellerEligibility,
        boolean listingCreationAllowed,
        boolean listingSubmissionAllowed,
        long ruleVersion,
        List<CategoryAttributeResponse> attributes,
        List<CategorySellerGuidanceResponse> sellerGuidance
) {
}
