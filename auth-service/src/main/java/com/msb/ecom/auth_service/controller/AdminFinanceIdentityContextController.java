package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/finance-context")
@RequiredArgsConstructor
public class AdminFinanceIdentityContextController {
    private final AdminAuthorizationService authorization;

    @GetMapping
    public ApiDataResponse<AdminIdentityLabelsResponse> context(
            @RequestParam(defaultValue = "") Set<String> userIds,
            @RequestParam(defaultValue = "") Set<String> businessIds) {
        return new ApiDataResponse<>(authorization.financeIdentityLabels(userIds, businessIds));
    }
}
