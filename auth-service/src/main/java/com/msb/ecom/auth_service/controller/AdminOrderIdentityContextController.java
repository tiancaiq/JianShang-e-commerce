package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminOrderIdentityContextResponse;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.service.AdminOrderIdentityContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/order-context")
@RequiredArgsConstructor
public class AdminOrderIdentityContextController {
    private final AdminOrderIdentityContextService service;

    @GetMapping
    public ApiDataResponse<AdminOrderIdentityContextResponse> context(
            @RequestParam(defaultValue = "") Set<String> userIds,
            @RequestParam(defaultValue = "") Set<String> businessIds) {
        return new ApiDataResponse<>(service.resolve(userIds, businessIds));
    }
}
