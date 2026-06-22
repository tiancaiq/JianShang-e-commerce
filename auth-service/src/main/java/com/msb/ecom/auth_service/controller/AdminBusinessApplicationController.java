package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessApplicationDecisionRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.service.BusinessApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/business-applications")
@RequiredArgsConstructor
public class AdminBusinessApplicationController {

    private final BusinessApplicationService businessApplicationService;

    @PostMapping("/{id}/decision")
    public ApiDataResponse<BusinessApplicationResponse> decide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @Valid @RequestBody BusinessApplicationDecisionRequest request) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required");
        }
        return new ApiDataResponse<>(businessApplicationService.decide(jwt, id, request));
    }
}
