package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.reporting.ReportContracts.CreateReportRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.SubmissionResult;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService service;

    @PostMapping
    public ResponseEntity<ApiDataResponse<SubmissionResult>> submit(
            @RequestBody CreateReportRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiDataResponse<>(
                service.submit(request, CorrelationIdFilter.current(httpRequest))));
    }
}
