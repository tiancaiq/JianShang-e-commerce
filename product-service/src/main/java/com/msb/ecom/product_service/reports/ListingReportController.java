package com.msb.ecom.product_service.reports;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ListingReportController {

    private final ListingReportService service;
    private final ListingReportRequestParser parser;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ListingReportResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        service.requireEnabled();
        ListingReportService.CreateResult result = service.create(
                parser.parse(body),
                idempotencyKey,
                CorrelationIdFilter.current(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping("/{reportId}")
    public ListingReportResponse get(@PathVariable String reportId) {
        return service.get(reportId);
    }
}
