package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.BusinessStoreCommerceEligibilityResponse;
import com.msb.ecom.auth_service.service.BusinessStoreCommerceEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/businesses")
@RequiredArgsConstructor
public class InternalBusinessStoreCommerceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final BusinessStoreCommerceEligibilityService eligibilityService;

    @GetMapping("/{businessId}/stores/{storeId}/commerce-eligibility")
    public BusinessStoreCommerceEligibilityResponse get(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String businessId,
            @PathVariable String storeId) {
        return eligibilityService.get(internalToken, businessId, storeId);
    }
}
