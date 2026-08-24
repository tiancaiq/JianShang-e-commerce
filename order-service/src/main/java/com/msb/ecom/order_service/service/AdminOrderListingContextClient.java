package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

public interface AdminOrderListingContextClient {
    Context find(String listingId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Context(
            String listingId,
            String title,
            BigDecimal price,
            String currency,
            String status,
            long version,
            List<Enforcement> enforcement) {
        public Context {
            enforcement = enforcement == null ? List.of() : List.copyOf(enforcement);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Enforcement(String actionType, List<String> scopes) {
        public Enforcement {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
