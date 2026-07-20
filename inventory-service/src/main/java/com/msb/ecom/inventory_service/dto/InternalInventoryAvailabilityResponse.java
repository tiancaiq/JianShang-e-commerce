package com.msb.ecom.inventory_service.dto;

public record InternalInventoryAvailabilityResponse(
        String listingId,
        String businessId,
        boolean initialized,
        int onHand,
        int reserved,
        int available,
        long version
) {
    public static InternalInventoryAvailabilityResponse uninitialized(String listingId) {
        return new InternalInventoryAvailabilityResponse(
                listingId,
                null,
                false,
                0,
                0,
                0,
                0);
    }
}
