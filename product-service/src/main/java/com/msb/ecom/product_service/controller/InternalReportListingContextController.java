package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.InternalReportListingContext;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.ReportListingContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/reports/listings")
public class InternalReportListingContextController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final ReportListingContextService service;
    private final byte[] expectedToken;

    public InternalReportListingContextController(ReportListingContextService service,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.service = service;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{listingId}")
    public InternalReportListingContext context(
            @PathVariable String listingId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken) {
        byte[] actual = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException("Internal report target authentication is required.");
        }
        return service.context(listingId);
    }
}
