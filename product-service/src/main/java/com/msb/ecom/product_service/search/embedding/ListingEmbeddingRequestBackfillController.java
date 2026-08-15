package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.product_service.search.BodylessAdminCommandValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search/listings/embedding-request-backfills")
@RequiredArgsConstructor
public class ListingEmbeddingRequestBackfillController {
    private final ListingEmbeddingRequestBackfillOperatorService service;

    @PostMapping
    public ListingEmbeddingRequestBackfillResponse start(HttpServletRequest request) {
        rejectBody(request);
        return service.start(CorrelationIdFilter.current(request));
    }

    @GetMapping("/{runId}")
    public ListingEmbeddingRequestBackfillResponse status(@PathVariable String runId) {
        return service.status(runId);
    }

    @PostMapping("/{runId}/resume")
    public ListingEmbeddingRequestBackfillResponse resume(
            @PathVariable String runId,
            HttpServletRequest request) {
        rejectBody(request);
        return service.resume(runId, CorrelationIdFilter.current(request));
    }

    // Commands accept transport framing but reject the first decoded payload byte.
    private void rejectBody(HttpServletRequest request) {
        BodylessAdminCommandValidator.rejectDecodedBodyBytes(
                request,
                exception -> new ListingEmbeddingRequestBackfillException(
                        ListingEmbeddingRequestBackfillException.Kind.UNAVAILABLE,
                        "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE",
                        exception));
    }
}
