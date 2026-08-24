package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Request;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Response;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionService;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/admin/appeals/{appealId}/listing-enforcement-resolution")
public class InternalListingAppealResolutionController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final ListingAppealResolutionService service;
    private final byte[] expectedToken;

    public InternalListingAppealResolutionController(ListingAppealResolutionService service,
            @Value("${commerce.internal-service-token}") String token) {
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/dry-run")
    public Response preview(@PathVariable String appealId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody Request request) {
        requireToken(token);
        return service.preview(appealId, request);
    }

    @PostMapping
    public Response execute(@PathVariable String appealId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody Request request) {
        requireToken(token);
        return service.execute(appealId, request);
    }

    private void requireToken(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException(
                    "Internal listing appeal resolution authentication is required.");
        }
    }
}
