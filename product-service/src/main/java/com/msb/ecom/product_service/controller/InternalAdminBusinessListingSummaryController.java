package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.AdminBusinessListingSummaryContracts.Envelope;
import com.msb.ecom.product_service.dto.AdminBusinessListingSummaryContracts.Request;
import com.msb.ecom.product_service.service.AdminBusinessListingSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/businesses")
@RequiredArgsConstructor
public class InternalAdminBusinessListingSummaryController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final AdminBusinessListingSummaryService service;

    @PostMapping("/listing-summaries")
    public Envelope summaries(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody Request request) {
        return service.summaries(internalToken, request);
    }
}
