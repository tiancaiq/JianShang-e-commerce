package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessMembershipResponse;
import com.msb.ecom.auth_service.service.BusinessMembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses")
@RequiredArgsConstructor
public class BusinessMembershipController {

    private final BusinessMembershipService businessMembershipService;

    @GetMapping("/{businessId}/membership/me")
    public ApiDataResponse<BusinessMembershipResponse> me(@PathVariable String businessId) {
        return new ApiDataResponse<>(businessMembershipService.getCurrentMembership(businessId));
    }
}
