package com.msb.ecom.product_service.search.hybrid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/marketplace/listings")
public class InternalAgentHybridSearchController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final ListingHybridSearchService service;

    public InternalAgentHybridSearchController(ListingHybridSearchService service) {
        this.service = service;
    }

    @PostMapping("/hybrid-search")
    public ListingHybridSearchResponse search(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody(required = false) byte[] body) {
        return service.search(internalToken, body);
    }
}
