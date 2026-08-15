package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesBatchRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesBatchResponse;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesResponse;
import com.msb.ecom.auth_service.service.BusinessCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/businesses")
@RequiredArgsConstructor
public class InternalBusinessCapabilityController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final BusinessCapabilityService service;

    @PostMapping("/capabilities/evaluate")
    public EvaluateCapabilitiesResponse evaluate(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody EvaluateCapabilitiesRequest request) {
        return service.evaluateInternal(internalToken, request);
    }

    @PostMapping("/capabilities/evaluate-batch")
    public EvaluateCapabilitiesBatchResponse evaluateBatch(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody EvaluateCapabilitiesBatchRequest request) {
        return service.evaluateBatchInternal(internalToken, request);
    }
}
