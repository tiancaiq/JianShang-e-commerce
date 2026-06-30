package com.msb.ecom.product_service.dto;

import java.util.List;

public record AdminListingModerationCaseDetailResponse(
        AdminListingModerationCaseResponse moderationCase,
        ListingDraftResponse listing,
        List<ListingModerationDecisionResponse> decisions
) {
}
