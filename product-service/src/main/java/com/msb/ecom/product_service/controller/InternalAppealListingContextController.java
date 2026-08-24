package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.InternalAppealListingContext;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AppealListingContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/internal/appeals/listing-enforcements")
public class InternalAppealListingContextController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final AppealListingContextService service;
    private final byte[] expectedToken;

    public InternalAppealListingContextController(AppealListingContextService service,
            @Value("${commerce.internal-service-token}") String token) {
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{actionId}")
    public InternalAppealListingContext byAction(@PathVariable String actionId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        return service.byActionId(actionId);
    }

    @PostMapping("/mine")
    public List<InternalAppealListingContext> mine(@RequestBody MineRequest request,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new ListingAuthorizationException("A trusted actor is required.");
        }
        return service.activeForActor(request.userId().trim(), request.businessIds());
    }

    private void requireToken(String token) {
        byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException("Internal appeal context authentication is required.");
        }
    }

    public record MineRequest(String userId, Set<String> businessIds) { }
}
