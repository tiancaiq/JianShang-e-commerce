package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.CreateRequest;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.Detail;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.Preview;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.RevokeRequest;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.TimelineResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/listings/{listingId}/enforcements")
@RequiredArgsConstructor
public class AdminListingEnforcementController {
    private final EnforcementService service;

    @GetMapping
    public Detail detail(@PathVariable String listingId) {
        return service.detail(listingId);
    }

    @GetMapping("/timeline")
    public TimelineResponse timeline(@PathVariable String listingId) {
        return new TimelineResponse(service.timeline(listingId));
    }

    @PostMapping("/dry-run")
    public Preview preview(@PathVariable String listingId, @RequestBody CreateRequest request) {
        return service.preview(createCommand(listingId, request, true));
    }

    @PostMapping
    public Result create(@PathVariable String listingId, @RequestBody CreateRequest request) {
        return service.create(createCommand(listingId, request, false));
    }

    @PostMapping("/{enforcementId}/revoke/dry-run")
    public Preview previewRevoke(@PathVariable String listingId, @PathVariable String enforcementId,
            @RequestBody RevokeRequest request) {
        return service.previewRevocation(listingId, revokeCommand(enforcementId, request, true));
    }

    @PostMapping("/{enforcementId}/revoke")
    public Result revoke(@PathVariable String listingId, @PathVariable String enforcementId,
            @RequestBody RevokeRequest request) {
        return service.revokeForListing(listingId, revokeCommand(enforcementId, request, false));
    }

    private CreateCommand createCommand(String listingId, CreateRequest request, boolean dryRun) {
        if (request == null) return null;
        return new CreateCommand(TargetType.LISTING, listingId, request.actionType(), request.scopes(),
                request.reasonCode(), request.reason(), null, request.effectiveAt(), request.expiresAt(),
                request.expectedListingVersion(), request.idempotencyKey(), request.safeMetadata(), dryRun);
    }

    private RevokeCommand revokeCommand(String enforcementId, RevokeRequest request, boolean dryRun) {
        if (request == null) return null;
        return new RevokeCommand(enforcementId, request.expectedEnforcementVersion(), request.reasonCode(),
                request.reason(), request.idempotencyKey(), request.safeMetadata(), dryRun);
    }
}
