package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.InternalReportListingContext;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.ReportListingContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/support/listings")
public class InternalSupportListingContextController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";
    private final ReportListingContextService service;
    private final byte[] expectedToken;

    public InternalSupportListingContextController(ReportListingContextService service,
            @Value("${commerce.internal-service-token}") String token) {
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{listingId}")
    public ResponseEntity<Map<String, Object>> context(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @PathVariable String listingId,
            @RequestParam(required = false) String requesterUserId) {
        requireAuthenticated(token);
        InternalReportListingContext listing = service.context(listingId);
        boolean requesterMayLink = requesterUserId == null || listing.reportable()
                || requesterUserId.equals(listing.individualSellerUserId());
        if (!requesterMayLink) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("safeLabel", listing.title());
        result.put("listingId", listing.listingId());
        result.put("sellerType", listing.sellerType());
        result.put("businessId", listing.businessId());
        result.put("storeId", listing.storeId());
        result.put("status", listing.status());
        result.put("price", listing.price());
        result.put("currency", listing.currency());
        result.put("reportable", listing.reportable());
        return ResponseEntity.ok(result);
    }

    private void requireAuthenticated(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException("Internal support target authentication is required.");
        }
    }
}
