package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.AdminDashboardSummaryResponse;
import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.PlatformAdminResponse;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuthorizationController {

    private final AdminAuthorizationService adminAuthorizationService;

    @GetMapping("/me")
    public ApiDataResponse<PlatformAdminResponse> me() {
        return new ApiDataResponse<>(adminAuthorizationService.requireCurrentPlatformAdmin());
    }

    @GetMapping("/dashboard-summary")
    public ApiDataResponse<AdminDashboardSummaryResponse> dashboardSummary() {
        return new ApiDataResponse<>(adminAuthorizationService.dashboardSummary());
    }

    @GetMapping("/identity-labels")
    public ApiDataResponse<AdminIdentityLabelsResponse> identityLabels(
            @RequestParam(defaultValue = "") Set<String> userIds,
            @RequestParam(defaultValue = "") Set<String> businessIds) {
        return new ApiDataResponse<>(adminAuthorizationService.identityLabels(userIds, businessIds));
    }
}
