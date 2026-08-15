package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.AgentMarketplaceAvailabilityResponse;
import com.msb.ecom.product_service.dto.AgentMarketplaceSearchResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.service.AgentMarketplaceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/internal/agent/marketplace/listings")
@RequiredArgsConstructor
public class InternalAgentMarketplaceSearchController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final AgentMarketplaceSearchService service;

    @GetMapping("/availability")
    public AgentMarketplaceAvailabilityResponse availability(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestParam String category,
            @RequestParam(defaultValue = "1") Integer limit) {
        return service.availability(internalToken, category, limit);
    }

    @GetMapping("/search")
    public AgentMarketplaceSearchResponse search(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestParam String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String county,
            @RequestParam(defaultValue = "20") Integer limit) {
        return service.search(
                internalToken,
                new PublicListingSearchRequest(
                        q,
                        categoryId,
                        condition,
                        minPrice,
                        maxPrice,
                        city,
                        county,
                        "newest",
                        null,
                        limit));
    }
}
