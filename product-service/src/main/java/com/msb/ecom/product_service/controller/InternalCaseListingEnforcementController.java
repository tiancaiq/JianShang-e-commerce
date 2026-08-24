package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.CreateRequest;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.Preview;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/admin/cases/{caseId}/listings/{listingId}/enforcements")
public class InternalCaseListingEnforcementController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final EnforcementService service;
    private final byte[] expectedToken;

    public InternalCaseListingEnforcementController(EnforcementService service,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.service = service;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/dry-run")
    public Preview preview(@PathVariable String caseId, @PathVariable String listingId,
                           @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
                           @RequestBody CreateRequest request) {
        authenticate(token);
        return service.preview(command(caseId, listingId, request, true));
    }

    @PostMapping
    public Result create(@PathVariable String caseId, @PathVariable String listingId,
                         @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
                         @RequestBody CreateRequest request) {
        authenticate(token);
        return service.create(command(caseId, listingId, request, false));
    }

    private CreateCommand command(String caseId, String listingId, CreateRequest request, boolean dryRun) {
        if (request == null) return null;
        Map<String, String> metadata = request.safeMetadata() == null ? Map.of() : request.safeMetadata();
        return new CreateCommand(TargetType.LISTING, listingId, request.actionType(), request.scopes(),
                request.reasonCode(), request.reason(), caseId, request.effectiveAt(), request.expiresAt(),
                request.expectedListingVersion(), request.idempotencyKey(), metadata, dryRun);
    }

    private void authenticate(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException("Internal case enforcement authentication is required.");
        }
    }
}
