package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.auth_service.reporting.ReportContracts.ListingTargetContext;

import java.util.Optional;

public interface ProductReportTargetClient {
    Optional<ListingTargetContext> listing(String listingId);
}
