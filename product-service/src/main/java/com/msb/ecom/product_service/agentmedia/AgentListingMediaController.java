package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/listings")
@RequiredArgsConstructor
public class AgentListingMediaController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final AgentListingMediaService service;

    @PostMapping("/{listingId}/draft-media")
    public AgentListingMediaResponse readOwnedDraftMedia(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String listingId,
            @Valid @RequestBody AgentListingMediaRequest request,
            HttpServletRequest httpRequest) {
        return service.readOwnedDraftMedia(
                internalToken,
                listingId,
                request,
                CorrelationIdFilter.current(httpRequest));
    }
}
