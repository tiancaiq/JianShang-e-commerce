package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessApplicationDraftRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.service.BusinessApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
            @Valid @RequestBody BusinessApplicationDraftRequest request) {
        return new ApiDataResponse<>(businessApplicationService.createDraft(request));
    }

    @GetMapping("/me")
    public ApiDataResponse<BusinessApplicationResponse> getCurrent() {
        return new ApiDataResponse<>(businessApplicationService.getCurrentOwned());
    }

    @GetMapping("/{id}")
    public ApiDataResponse<BusinessApplicationResponse> get(@PathVariable String id) {
        return new ApiDataResponse<>(businessApplicationService.getOwned(id));
    }

    @PatchMapping("/{id}")
    public ApiDataResponse<BusinessApplicationResponse> update(
            @PathVariable String id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody BusinessApplicationDraftRequest request) {
        return new ApiDataResponse<>(businessApplicationService.updateDraft(
                id,
                BusinessApplicationVersionHeader.parse(ifMatch),
                request));
    }

    @PostMapping("/{id}/submit")
    public ApiDataResponse<BusinessApplicationResponse> submit(
            @PathVariable String id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return new ApiDataResponse<>(businessApplicationService.submit(
                id,
                BusinessApplicationVersionHeader.parse(ifMatch)));
    }

}
