package com.msb.ecom.product_service.search;

public record ListingSearchPromotionEligibility(
        boolean canCatchUp,
        boolean canPromote,
        boolean canRecover
) {
    public static final ListingSearchPromotionEligibility NONE =
            new ListingSearchPromotionEligibility(false, false, false);
}
