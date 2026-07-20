package com.msb.ecom.order_service.service;

import java.util.Optional;

public interface BusinessStoreEligibilityClient {

    Optional<Eligibility> find(String businessId, String storeId);

    record Eligibility(
            String businessId,
            String storeId,
            boolean eligible,
            String businessStatus,
            String storeStatus
    ) {
    }
}
