package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.PublicBusinessStoreSearchResponse;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping("/api/v1/public/business-stores/search")
    public ApiDataResponse<List<PublicBusinessStoreSearchResponse>> businessStores(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "") Set<String> businessIds,
            @RequestParam(defaultValue = "") Set<String> storeIds) {
        return new ApiDataResponse<>(adminAuthorizationService.publicBusinessStores(q, businessIds, storeIds));
    }
}
