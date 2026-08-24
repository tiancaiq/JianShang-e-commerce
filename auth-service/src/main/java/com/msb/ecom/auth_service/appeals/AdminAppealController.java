package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.AppealContracts.*;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin/appeals")
@RequiredArgsConstructor
public class AdminAppealController {
    private final AppealService service;
    private final AppealResolutionService resolutionService;

    @GetMapping
    public ApiDataResponse<Page> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) TargetType targetType,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) ReasonCode reasonCode,
            @RequestParam(required = false) Assignment assignment,
            @RequestParam(required = false) Instant submittedFrom,
            @RequestParam(required = false) Instant submittedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "submittedAt,desc") String sort) {
        return new ApiDataResponse<>(service.search(q, targetType, status, reasonCode, assignment,
                submittedFrom, submittedTo, page, size, sort));
    }

    @GetMapping("/{appealId}")
    public ApiDataResponse<Detail> detail(@PathVariable String appealId) {
        return new ApiDataResponse<>(service.detail(appealId));
    }

    @PostMapping("/{appealId}/claim")
    public ApiDataResponse<Detail> claim(@PathVariable String appealId, @RequestBody VersionRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(service.claim(appealId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/release")
    public ApiDataResponse<Detail> release(@PathVariable String appealId, @RequestBody VersionRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(service.release(appealId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/start-review")
    public ApiDataResponse<Detail> start(@PathVariable String appealId, @RequestBody VersionRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(service.startReview(appealId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/notes")
    public ApiDataResponse<Detail> note(@PathVariable String appealId, @RequestBody NoteRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(service.addNote(appealId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/review")
    public ApiDataResponse<Detail> review(@PathVariable String appealId, @RequestBody ReviewRequest request,
            HttpServletRequest http) {
        return new ApiDataResponse<>(service.review(appealId, request, CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/resolution/dry-run")
    public ApiDataResponse<ResolutionPreview> previewResolution(@PathVariable String appealId,
            @RequestBody ResolutionPreviewRequest request, HttpServletRequest http) {
        return new ApiDataResponse<>(resolutionService.preview(appealId, request,
                CorrelationIdFilter.current(http)));
    }

    @PostMapping("/{appealId}/resolution")
    public ApiDataResponse<Detail> resolve(@PathVariable String appealId,
            @RequestBody ResolutionRequest request, HttpServletRequest http) {
        return new ApiDataResponse<>(resolutionService.resolve(appealId, request,
                CorrelationIdFilter.current(http)));
    }
}
