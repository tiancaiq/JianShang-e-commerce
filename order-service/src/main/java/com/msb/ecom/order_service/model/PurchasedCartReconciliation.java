package com.msb.ecom.order_service.model;

import java.time.Instant;
import java.util.List;

public record PurchasedCartReconciliation(
        String checkoutId,
        String cartOwnerKey,
        long cartVersion,
        List<Line> lines
) {
    public record Line(String listingId, Instant lineIdentity) {
    }
}
