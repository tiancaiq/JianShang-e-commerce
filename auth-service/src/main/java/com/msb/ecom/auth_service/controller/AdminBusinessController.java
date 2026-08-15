package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.CreateEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.Detail;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EnforcementPreview;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.RevokeEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.SearchPage;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.TimelineEntry;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.service.AdminBusinessService;
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
@RequestMapping("/api/v1/admin/businesses")
@RequiredArgsConstructor
public class AdminBusinessController {

    private final AdminBusinessService service;

    @GetMapping
    public ApiDataResponse<SearchPage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String businessState,
            @RequestParam(required = false) String enforcementState,
            @RequestParam(required = false) Scope scope,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return new ApiDataResponse<>(service.search(q, businessState, enforcementState, scope,
                createdFrom, createdTo, page, size, sort));
    }

    @GetMapping("/{businessId}")
    public ApiDataResponse<Detail> detail(@PathVariable String businessId) {
        return new ApiDataResponse<>(service.detail(businessId));
    }

    @GetMapping("/{businessId}/timeline")
    public ApiDataResponse<List<TimelineEntry>> timeline(@PathVariable String businessId) {
        return new ApiDataResponse<>(service.timeline(businessId));
    }

    @PostMapping("/{businessId}/enforcements/dry-run")
    public ApiDataResponse<EnforcementPreview> createPreview(
            @PathVariable String businessId,
            @RequestBody CreateEnforcementRequest request) {
        return new ApiDataResponse<>(service.createPreview(businessId, request));
    }

    @PostMapping("/{businessId}/enforcements")
    public ApiDataResponse<Result> create(
            @PathVariable String businessId,
            @RequestBody CreateEnforcementRequest request) {
        return new ApiDataResponse<>(service.createConfirmed(businessId, request));
    }

    @PostMapping("/{businessId}/enforcements/{enforcementId}/revoke/dry-run")
    public ApiDataResponse<EnforcementPreview> revokePreview(
            @PathVariable String businessId,
            @PathVariable String enforcementId,
            @RequestBody RevokeEnforcementRequest request) {
        return new ApiDataResponse<>(service.revokePreview(businessId, enforcementId, request));
    }

    @PostMapping("/{businessId}/enforcements/{enforcementId}/revoke")
    public ApiDataResponse<Result> revoke(
            @PathVariable String businessId,
            @PathVariable String enforcementId,
            @RequestBody RevokeEnforcementRequest request) {
        return new ApiDataResponse<>(service.revokeConfirmed(businessId, enforcementId, request));
    }
}
