package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;

@RestController
@RequestMapping("/api/v1/admin/cases")
@RequiredArgsConstructor
public class InvestigationCaseController {
    private final InvestigationCaseService service;
    private final CaseEnforcementService caseEnforcement;

    @GetMapping
    public ApiDataResponse<Page> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Status status,
                                        @RequestParam(required = false) ReportContracts.Severity severity,
                                        @RequestParam(required = false) ReportContracts.TargetType targetType,
                                        @RequestParam(required = false) Assignment assignment,
                                        @RequestParam(required = false) Instant createdFrom,
                                        @RequestParam(required = false) Instant createdTo,
                                        @RequestParam(required = false) Instant updatedFrom,
                                        @RequestParam(required = false) Instant updatedTo,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size,
                                        @RequestParam(defaultValue = "updatedAt,desc") String sort) {
        return new ApiDataResponse<>(service.search(q, status, severity, targetType, assignment,
                createdFrom, createdTo, updatedFrom, updatedTo, page, size, sort));
    }

    @GetMapping("/{caseId}")
    public ApiDataResponse<Detail> detail(@PathVariable String caseId) {
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PostMapping("/{caseId}/claim")
    public ApiDataResponse<Detail> claim(@PathVariable String caseId, @RequestBody VersionRequest request,
                                        HttpServletRequest http) {
        return new ApiDataResponse<>(service.claim(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/release")
    public ApiDataResponse<Detail> release(@PathVariable String caseId, @RequestBody VersionRequest request,
                                          HttpServletRequest http) {
        return new ApiDataResponse<>(service.release(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/start")
    public ApiDataResponse<Detail> start(@PathVariable String caseId, @RequestBody ReasonRequest request,
                                        HttpServletRequest http) {
        return new ApiDataResponse<>(service.start(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/reports")
    public ApiDataResponse<Detail> linkReport(@PathVariable String caseId, @RequestBody LinkReportRequest request,
                                             HttpServletRequest http) {
        return new ApiDataResponse<>(service.linkReport(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/reports/{reportId}/unlink")
    public ApiDataResponse<Detail> unlinkReport(@PathVariable String caseId, @PathVariable String reportId,
                                               @RequestBody UnlinkReportRequest request,
                                               HttpServletRequest http) {
        return new ApiDataResponse<>(service.unlinkReport(caseId, reportId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/targets")
    public ApiDataResponse<Detail> linkTarget(@PathVariable String caseId, @RequestBody LinkTargetRequest request,
                                             HttpServletRequest http) {
        return new ApiDataResponse<>(service.linkTarget(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/targets/{targetType}/{targetId}/unlink")
    public ApiDataResponse<Detail> unlinkTarget(@PathVariable String caseId,
                                               @PathVariable ReportContracts.TargetType targetType,
                                               @PathVariable String targetId,
                                               @RequestBody UnlinkTargetRequest request,
                                               HttpServletRequest http) {
        return new ApiDataResponse<>(service.unlinkTarget(caseId, targetType, targetId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/notes")
    public ApiDataResponse<Detail> addNote(@PathVariable String caseId, @RequestBody AddNoteRequest request,
                                          HttpServletRequest http) {
        return new ApiDataResponse<>(service.addNote(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/evidence")
    public ApiDataResponse<Detail> addEvidence(@PathVariable String caseId,
                                              @RequestBody AddEvidenceRequest request,
                                              HttpServletRequest http) {
        return new ApiDataResponse<>(service.addEvidence(caseId, request, correlation(http)));
    }

    @PatchMapping("/{caseId}/severity")
    public ApiDataResponse<Detail> severity(@PathVariable String caseId, @RequestBody SeverityRequest request,
                                           HttpServletRequest http) {
        return new ApiDataResponse<>(service.severity(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/ready-for-action")
    public ApiDataResponse<Detail> readyForAction(@PathVariable String caseId,
                                                 @RequestBody ReasonRequest request,
                                                 HttpServletRequest http) {
        return new ApiDataResponse<>(service.readyForAction(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/close-no-action")
    public ApiDataResponse<Detail> closeNoAction(@PathVariable String caseId,
                                                @RequestBody CloseRequest request,
                                                HttpServletRequest http) {
        return new ApiDataResponse<>(service.closeNoAction(caseId, request, correlation(http)));
    }

    @PostMapping("/{caseId}/enforcement-proposals")
    public ApiDataResponse<Detail> createProposal(@PathVariable String caseId,
                                                  @RequestBody CreateProposalRequest request,
                                                  HttpServletRequest http) {
        caseEnforcement.create(caseId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PatchMapping("/{caseId}/enforcement-proposals/{proposalId}")
    public ApiDataResponse<Detail> updateProposal(@PathVariable String caseId, @PathVariable String proposalId,
                                                  @RequestBody UpdateProposalRequest request,
                                                  HttpServletRequest http) {
        caseEnforcement.update(caseId, proposalId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PostMapping("/{caseId}/enforcement-proposals/{proposalId}/dry-run")
    public ApiDataResponse<Detail> dryRunProposal(@PathVariable String caseId, @PathVariable String proposalId,
                                                  @RequestBody ProposalVersionRequest request,
                                                  HttpServletRequest http) {
        caseEnforcement.dryRun(caseId, proposalId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PostMapping("/{caseId}/enforcement-proposals/{proposalId}/execute")
    public ApiDataResponse<Detail> executeProposal(@PathVariable String caseId, @PathVariable String proposalId,
                                                   @RequestBody ExecuteProposalRequest request,
                                                   HttpServletRequest http) {
        caseEnforcement.execute(caseId, proposalId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PostMapping("/{caseId}/enforcement-proposals/{proposalId}/cancel")
    public ApiDataResponse<Detail> cancelProposal(@PathVariable String caseId, @PathVariable String proposalId,
                                                  @RequestBody CancelProposalRequest request,
                                                  HttpServletRequest http) {
        caseEnforcement.cancel(caseId, proposalId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    @PostMapping("/{caseId}/close-actioned")
    public ApiDataResponse<Detail> closeActioned(@PathVariable String caseId,
                                                @RequestBody CloseActionedRequest request,
                                                HttpServletRequest http) {
        caseEnforcement.closeActioned(caseId, request, correlation(http));
        return new ApiDataResponse<>(service.detail(caseId));
    }

    private static String correlation(HttpServletRequest request) {
        return CorrelationIdFilter.current(request);
    }
}
