package com.msb.ecom.product_service.reports;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class ListingReportTimeSource {
    Instant now() {
        return Instant.now();
    }
}
