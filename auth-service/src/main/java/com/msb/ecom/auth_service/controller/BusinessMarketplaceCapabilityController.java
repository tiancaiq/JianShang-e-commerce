package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.BusinessMarketplaceCapabilities;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.service.BusinessCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses")
@RequiredArgsConstructor
public class BusinessMarketplaceCapabilityController {

    private final BusinessCapabilityService service;

    @GetMapping("/{businessId}/marketplace-capabilities")
    public ApiDataResponse<BusinessMarketplaceCapabilities> currentMemberCapabilities(
            @PathVariable String businessId) {
        return new ApiDataResponse<>(service.currentMemberCapabilities(businessId));
    }
}
