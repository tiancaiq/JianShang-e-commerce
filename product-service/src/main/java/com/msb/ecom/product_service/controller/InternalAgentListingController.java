package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.knowledge.AgentListingCustomerServiceContext;
import com.msb.ecom.product_service.knowledge.AgentListingCustomerServiceContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/listings")
@RequiredArgsConstructor
public class InternalAgentListingController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final AgentListingCustomerServiceContextService service;

    @GetMapping("/{listingId}/customer-service-context")
    public AgentListingCustomerServiceContext customerServiceContext(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String listingId) {
        return service.get(internalToken, listingId);
    }
}
