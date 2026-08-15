package com.msb.ecom.product_service.search.embedding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/discovery/embedding-requests")
public class ListingDiscoveryEmbeddingSourceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final ListingDiscoveryEmbeddingSourceService service;
    private final ListingDiscoveryEmbeddingResultService resultService;

    public ListingDiscoveryEmbeddingSourceController(
            ListingDiscoveryEmbeddingSourceService service,
            ListingDiscoveryEmbeddingResultService resultService) {
        this.service = service;
        this.resultService = resultService;
    }

    @GetMapping("/{requestId}/source")
    public ListingDiscoveryEmbeddingSourceResponse source(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String requestId) {
        return service.readExact(internalToken, requestId);
    }

    @PostMapping("/{requestId}/result")
    public ListingDiscoveryEmbeddingResultAcknowledgement result(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String requestId,
            @RequestBody(required = false) byte[] body) {
        return resultService.accept(internalToken, requestId, body);
    }
}
