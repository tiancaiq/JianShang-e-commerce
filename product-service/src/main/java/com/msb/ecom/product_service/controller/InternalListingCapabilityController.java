package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.EvaluateBatchRequest;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.EvaluateBatchResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/listings/capabilities")
public class InternalListingCapabilityController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final EnforcementService service;
    private final byte[] expectedToken;

    public InternalListingCapabilityController(EnforcementService service,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.service = service;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/evaluate-batch")
    public EvaluateBatchResponse evaluateBatch(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody EvaluateBatchRequest request) {
        byte[] actual = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException("Internal commerce service authentication is required.");
        }
        return service.evaluateBatch(request == null ? null : request.listingIds(),
                request == null ? null : request.scopes());
    }
}
