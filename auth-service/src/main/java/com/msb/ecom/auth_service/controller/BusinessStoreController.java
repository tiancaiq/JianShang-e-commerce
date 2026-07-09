package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessStoreContextResponse;
import com.msb.ecom.auth_service.dto.BusinessStoreResponse;
import com.msb.ecom.auth_service.dto.BusinessStoreUpdateRequest;
import com.msb.ecom.auth_service.service.BusinessStoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BusinessStoreController {

    private final BusinessStoreService businessStoreService;

    @GetMapping("/api/v1/businesses/me/store-context")
    public ApiDataResponse<BusinessStoreContextResponse> getCurrentStoreContext() {
        return new ApiDataResponse<>(businessStoreService.getCurrentStoreContext());
    }

    @GetMapping("/api/v1/businesses/{businessId}/store")
    public ApiDataResponse<BusinessStoreResponse> getBusinessStore(@PathVariable String businessId) {
        return new ApiDataResponse<>(businessStoreService.getBusinessStore(businessId));
    }

    @PatchMapping("/api/v1/businesses/{businessId}/store")
    public ApiDataResponse<BusinessStoreResponse> updateBusinessStore(
            @PathVariable String businessId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody BusinessStoreUpdateRequest request) {
        return new ApiDataResponse<>(businessStoreService.updateBusinessStore(
                businessId,
                BusinessStoreVersionHeader.parse(ifMatch),
                request));
    }

    @GetMapping("/api/v1/stores/{slug}")
    public ApiDataResponse<BusinessStoreResponse> getPublicStore(@PathVariable String slug) {
        return new ApiDataResponse<>(businessStoreService.getPublicStore(slug));
    }
}
