package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class PublicIdentityLabelController {

    private final AdminAuthorizationService adminAuthorizationService;

    @GetMapping({"/api/v1/public/seller-labels", "/api/v1/users/public-labels"})
    public ApiDataResponse<AdminIdentityLabelsResponse> sellerLabels(
            @RequestParam(defaultValue = "") Set<String> userIds,
            @RequestParam(defaultValue = "") Set<String> businessIds) {
        return new ApiDataResponse<>(adminAuthorizationService.publicSellerLabels(userIds, businessIds));
    }
}
