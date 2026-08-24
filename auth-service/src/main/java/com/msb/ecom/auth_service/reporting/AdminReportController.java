package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.reporting.ReportContracts.Assignment;
import com.msb.ecom.auth_service.reporting.ReportContracts.Detail;
import com.msb.ecom.auth_service.reporting.ReportContracts.DismissRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.Page;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReadyRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReasonCode;
import com.msb.ecom.auth_service.reporting.ReportContracts.Severity;
import com.msb.ecom.auth_service.reporting.ReportContracts.SeverityRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.Status;
import com.msb.ecom.auth_service.reporting.ReportContracts.TargetType;
import com.msb.ecom.auth_service.reporting.ReportContracts.VersionRequest;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {
    private final ReportService service;
    private final InvestigationCaseService investigationCases;

    @GetMapping
    public ApiDataResponse<Page> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) TargetType targetType,
            @RequestParam(required = false) ReasonCode reasonCode,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) Assignment assignment,
            @RequestParam(required = false) Boolean unresolved,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return new ApiDataResponse<>(service.search(q, status, targetType, reasonCode, severity, assignment,
                unresolved, createdFrom, createdTo, page, size, sort));
    }

    @GetMapping("/{reportId}")
    public ApiDataResponse<Detail> detail(@PathVariable String reportId) {
        return new ApiDataResponse<>(service.detail(reportId));
    }

    @PostMapping("/{reportId}/claim")
    public ApiDataResponse<Detail> claim(@PathVariable String reportId, @RequestBody VersionRequest request,
                                        HttpServletRequest http) {
        return new ApiDataResponse<>(service.claim(reportId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{reportId}/release")
    public ApiDataResponse<Detail> release(@PathVariable String reportId, @RequestBody VersionRequest request,
                                          HttpServletRequest http) {
        return new ApiDataResponse<>(service.release(reportId, request, CorrelationIdFilter.current(http)));
    }

    @PatchMapping("/{reportId}/severity")
    public ApiDataResponse<Detail> severity(@PathVariable String reportId, @RequestBody SeverityRequest request,
                                           HttpServletRequest http) {
        return new ApiDataResponse<>(service.severity(reportId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{reportId}/dismiss")
    public ApiDataResponse<Detail> dismiss(@PathVariable String reportId, @RequestBody DismissRequest request,
                                          HttpServletRequest http) {
        return new ApiDataResponse<>(service.dismiss(reportId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{reportId}/ready-for-investigation")
    public ApiDataResponse<Detail> ready(@PathVariable String reportId, @RequestBody ReadyRequest request,
                                        HttpServletRequest http) {
        return new ApiDataResponse<>(service.ready(reportId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{reportId}/investigation-case")
    public ApiDataResponse<InvestigationCaseContracts.Detail> createInvestigationCase(
            @PathVariable String reportId,
            @RequestBody InvestigationCaseContracts.CreateRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(investigationCases.createFromReport(
                reportId, request, CorrelationIdFilter.current(http)));
    }
}
