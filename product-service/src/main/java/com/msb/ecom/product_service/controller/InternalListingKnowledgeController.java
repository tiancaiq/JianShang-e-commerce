package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.knowledge.ListingKnowledgeExportResponse;
import com.msb.ecom.product_service.knowledge.ListingKnowledgeSourceResponse;
import com.msb.ecom.product_service.knowledge.ListingKnowledgeSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/knowledge/listings")
@RequiredArgsConstructor
public class InternalListingKnowledgeController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final ListingKnowledgeSourceService sourceService;

    @GetMapping("/{listingId}/versions/{sourceVersion}")
    public ListingKnowledgeSourceResponse exact(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String listingId,
            @PathVariable long sourceVersion) {
        return sourceService.exact(internalToken, listingId, sourceVersion);
    }

    @GetMapping("/export")
    public ListingKnowledgeExportResponse export(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return sourceService.export(internalToken, cursor, limit);
    }
}
