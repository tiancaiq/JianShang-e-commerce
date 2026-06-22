package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessApplicationDraftRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.service.BusinessApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-applications")
@RequiredArgsConstructor
public class BusinessApplicationController {

    private final BusinessApplicationService businessApplicationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDataResponse<BusinessApplicationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BusinessApplicationDraftRequest request) {
        requireAuthentication(jwt);
        return new ApiDataResponse<>(businessApplicationService.createDraft(jwt, request));
    }

    @GetMapping("/{id}")
    public ApiDataResponse<BusinessApplicationResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        requireAuthentication(jwt);
        return new ApiDataResponse<>(businessApplicationService.getOwned(jwt, id));
    }

    @PatchMapping("/{id}")
    public ApiDataResponse<BusinessApplicationResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody BusinessApplicationDraftRequest request) {
        requireAuthentication(jwt);
        return new ApiDataResponse<>(businessApplicationService.updateDraft(jwt, id, parseVersion(ifMatch), request));
    }

    @PostMapping("/{id}/submit")
    public ApiDataResponse<BusinessApplicationResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        requireAuthentication(jwt);
        return new ApiDataResponse<>(businessApplicationService.submit(jwt, id, parseVersion(ifMatch)));
    }

    private void requireAuthentication(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required");
        }
    }

    private Long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match must contain the current business application version");
        }
        String value = ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain the current business application version");
        }
    }

}
