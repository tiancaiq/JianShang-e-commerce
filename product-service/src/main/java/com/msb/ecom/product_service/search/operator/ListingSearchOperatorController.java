package com.msb.ecom.product_service.search.operator;

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
@RequestMapping("/api/v1/admin/search/listings/vector-rebuilds")
@RequiredArgsConstructor
public class ListingSearchOperatorController {
    private final ListingSearchOperatorService service;

    @PostMapping
    public ListingSearchOperatorResponse prepare(HttpServletRequest request) {
        rejectBody(request);
        return service.prepare(CorrelationIdFilter.current(request));
    }

    @GetMapping("/{runId}")
    public ListingSearchOperatorResponse status(@PathVariable String runId) {
        return service.status(runId);
    }

    @PostMapping("/{runId}/catch-up")
    public ListingSearchOperatorResponse catchUp(
            @PathVariable String runId,
            HttpServletRequest request) {
        rejectBody(request);
        return service.catchUp(runId, CorrelationIdFilter.current(request));
    }

    @PostMapping("/{runId}/promote")
    public ListingSearchOperatorResponse promote(
            @PathVariable String runId,
            HttpServletRequest request) {
        rejectBody(request);
        return service.promote(runId, CorrelationIdFilter.current(request));
    }

    @PostMapping("/{runId}/recover")
    public ListingSearchOperatorResponse recover(
            @PathVariable String runId,
            HttpServletRequest request) {
        rejectBody(request);
        return service.recover(runId, CorrelationIdFilter.current(request));
    }

    // Command endpoints deliberately accept no JSON or arbitrary OpenSearch controls.
    private void rejectBody(HttpServletRequest request) {
        BodylessAdminCommandValidator.rejectDecodedBodyBytes(
                request,
                ListingSearchOperatorUnavailableException::new);
    }
}
