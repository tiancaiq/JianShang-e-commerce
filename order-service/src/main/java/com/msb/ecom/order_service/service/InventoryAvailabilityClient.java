package com.msb.ecom.order_service.service;

public interface InventoryAvailabilityClient {

    Availability get(String listingId);

    record Availability(
            String listingId,
            String businessId,
            boolean initialized,
            int available,
            long version
    ) {
    }
}
