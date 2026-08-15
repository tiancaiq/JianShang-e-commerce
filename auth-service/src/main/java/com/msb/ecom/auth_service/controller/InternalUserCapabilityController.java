package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminUserContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.EvaluateCapabilitiesResponse;
import com.msb.ecom.auth_service.service.UserCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalUserCapabilityController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final UserCapabilityService service;

    @PostMapping("/capabilities/evaluate")
    public EvaluateCapabilitiesResponse evaluate(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody EvaluateCapabilitiesRequest request) {
        return service.evaluateInternal(internalToken, request);
    }
}
