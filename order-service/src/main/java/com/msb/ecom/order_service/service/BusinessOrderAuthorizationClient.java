package com.msb.ecom.order_service.service;

public interface BusinessOrderAuthorizationClient {

    // Resolves active membership and approved read capabilities for a path business.
    Access authorize(String accessToken, String businessId);

    // Resolves active membership with the fulfillment command capability.
    Access authorizeFulfillment(String accessToken, String businessId);

    record Access(
            String businessId,
            String userId,
            String role,
            boolean financeView
    ) {
    }
}
