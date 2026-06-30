package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessApplicationDecisionRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.service.BusinessApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/business-applications")
@RequiredArgsConstructor
public class AdminBusinessApplicationController {

    private final BusinessApplicationService businessApplicationService;

    @GetMapping
    public ApiDataResponse<List<BusinessApplicationResponse>> list(
            @RequestParam(required = false) String status) {
        return new ApiDataResponse<>(businessApplicationService.listAdminReviewQueue(status));
    }

    @GetMapping("/{id}")
    public ApiDataResponse<BusinessApplicationResponse> get(@PathVariable String id) {
        return new ApiDataResponse<>(businessApplicationService.getAdminApplication(id));
    }

    @PostMapping("/{id}/decision")
    public ApiDataResponse<BusinessApplicationResponse> decide(
            @PathVariable String id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody BusinessApplicationDecisionRequest request) {
        return new ApiDataResponse<>(businessApplicationService.decide(
                id,
                BusinessApplicationVersionHeader.parse(ifMatch),
                request));
    }
}
