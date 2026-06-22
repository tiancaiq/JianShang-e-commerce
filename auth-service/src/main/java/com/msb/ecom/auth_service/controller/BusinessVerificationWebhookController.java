package com.msb.ecom.auth_service.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.dto.BusinessVerificationWebhookRequest;
import com.msb.ecom.auth_service.service.BusinessApplicationService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/business-verification")
@RequiredArgsConstructor
public class BusinessVerificationWebhookController {

    private final BusinessApplicationService businessApplicationService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @PostMapping
    public ApiDataResponse<BusinessApplicationResponse> handle(
            @RequestHeader(name = "X-MSB-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        BusinessVerificationWebhookRequest request = readRequest(rawBody);
        return new ApiDataResponse<>(businessApplicationService.applyVerificationWebhook(request, signature, rawBody));
    }

    private BusinessVerificationWebhookRequest readRequest(String rawBody) {
        try {
            BusinessVerificationWebhookRequest request = objectMapper.readValue(
                    rawBody,
                    BusinessVerificationWebhookRequest.class);
            if (!validator.validate(request).isEmpty()) {
                throw new IllegalArgumentException("Business verification webhook request is invalid");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Business verification webhook request is invalid", exception);
        }
    }
}
