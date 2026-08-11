package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public interface InventoryAvailabilityClient {

    Availability get(String listingId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Availability(
            String listingId,
            String businessId,
            boolean initialized,
            int available,
            long version
    ) {
    }
}
