package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminUserContracts.CreateEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.Detail;
import com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview;
import com.msb.ecom.auth_service.dto.AdminUserContracts.RevokeEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.SearchPage;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TimelineEntry;
import com.msb.ecom.auth_service.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    @GetMapping
    public ApiDataResponse<SearchPage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String enforcementState,
            @RequestParam(required = false) Scope scope,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return new ApiDataResponse<>(service.search(
                q, enforcementState, scope, createdFrom, createdTo, page, size, sort));
    }

    @GetMapping("/{userId}")
    public ApiDataResponse<Detail> detail(@PathVariable String userId) {
        return new ApiDataResponse<>(service.detail(userId));
    }

    @GetMapping("/{userId}/timeline")
    public ApiDataResponse<List<TimelineEntry>> timeline(@PathVariable String userId) {
        return new ApiDataResponse<>(service.timeline(userId));
    }

    @PostMapping("/{userId}/enforcements/dry-run")
    public ApiDataResponse<EnforcementPreview> createPreview(
            @PathVariable String userId,
            @RequestBody CreateEnforcementRequest request) {
        return new ApiDataResponse<>(service.createPreview(userId, request));
    }

    @PostMapping("/{userId}/enforcements")
    public ApiDataResponse<Result> create(
            @PathVariable String userId,
            @RequestBody CreateEnforcementRequest request) {
        return new ApiDataResponse<>(service.createConfirmed(userId, request));
    }

    @PostMapping("/{userId}/enforcements/{enforcementId}/revoke/dry-run")
    public ApiDataResponse<EnforcementPreview> revokePreview(
            @PathVariable String userId,
            @PathVariable String enforcementId,
            @RequestBody RevokeEnforcementRequest request) {
        return new ApiDataResponse<>(service.revokePreview(userId, enforcementId, request));
    }

    @PostMapping("/{userId}/enforcements/{enforcementId}/revoke")
    public ApiDataResponse<Result> revoke(
            @PathVariable String userId,
            @PathVariable String enforcementId,
            @RequestBody RevokeEnforcementRequest request) {
        return new ApiDataResponse<>(service.revokeConfirmed(userId, enforcementId, request));
    }
}
