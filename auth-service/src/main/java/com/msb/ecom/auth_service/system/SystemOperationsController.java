package com.msb.ecom.auth_service.system;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/system")
@RequiredArgsConstructor
public class SystemOperationsController {
    private final SystemOperationsService service;

    @GetMapping("/summary")
    public ResponseEntity<SystemContracts.SystemSummary> summary() {
        return ok(service.summary());
    }

    @GetMapping("/health")
    public ResponseEntity<List<SystemContracts.ServiceHealth>> health() {
        return ok(service.health());
    }

    @GetMapping("/jobs")
    public ResponseEntity<SystemContracts.Page<SystemContracts.JobSummary>> jobs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean retryable,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ok(this.service.jobs(service, jobType, status, retryable, page, size));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<SystemContracts.JobSummary> job(@PathVariable String jobId) {
        return ok(service.job(jobId));
    }

    @PostMapping("/jobs/{jobId}/retry/dry-run")
    public ResponseEntity<SystemContracts.MaintenancePreview> previewJob(
            @PathVariable String jobId,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return ok(service.preview("JOB", jobId, "RETRY_JOB", request,
                CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<SystemContracts.MaintenanceResult> retryJob(
            @PathVariable String jobId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return accepted(service.execute("JOB", jobId, "RETRY_JOB", request, key,
                CorrelationIdFilter.current(servlet)));
    }

    @GetMapping("/outbox")
    public ResponseEntity<SystemContracts.Page<SystemContracts.OutboxSummary>> outbox(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ok(this.service.outbox(service, eventType, status, correlationId, page, size));
    }

    @GetMapping("/outbox/{eventId}")
    public ResponseEntity<SystemContracts.OutboxSummary> outboxEvent(@PathVariable String eventId) {
        return ok(service.outboxEvent(eventId));
    }

    @PostMapping("/outbox/{eventId}/retry/dry-run")
    public ResponseEntity<SystemContracts.MaintenancePreview> previewOutbox(
            @PathVariable String eventId,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return ok(service.preview("OUTBOX_EVENT", eventId, "RETRY_OUTBOX", request,
                CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/outbox/{eventId}/retry")
    public ResponseEntity<SystemContracts.MaintenanceResult> retryOutbox(
            @PathVariable String eventId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return accepted(service.execute("OUTBOX_EVENT", eventId, "RETRY_OUTBOX", request, key,
                CorrelationIdFilter.current(servlet)));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<SystemContracts.Page<SystemContracts.ReconciliationIssue>> reconciliation(
            @RequestParam(required = false) String issueType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ok(service.reconciliation(issueType, status, page, size));
    }

    @GetMapping("/inventory")
    public ResponseEntity<SystemContracts.Page<SystemContracts.InventoryIssue>> inventory(
            @RequestParam(required = false) String issueType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ok(service.inventory(issueType, page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<List<SystemContracts.SearchStatus>> search() {
        return ok(service.search());
    }

    @PostMapping("/search/listings/{listingId}/reindex/dry-run")
    public ResponseEntity<SystemContracts.MaintenancePreview> previewReindex(
            @PathVariable String listingId,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return ok(service.preview("SEARCH_LISTING", listingId, "REINDEX_LISTING", request,
                CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/search/listings/{listingId}/reindex")
    public ResponseEntity<SystemContracts.MaintenanceResult> reindex(
            @PathVariable String listingId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SystemContracts.MaintenanceRequest request,
            HttpServletRequest servlet) {
        return accepted(service.execute("SEARCH_LISTING", listingId, "REINDEX_LISTING",
                request, key, CorrelationIdFilter.current(servlet)));
    }

    @GetMapping("/features")
    public ResponseEntity<List<SystemContracts.FeatureState>> features() {
        return ok(service.features());
    }

    @GetMapping("/recent-operations")
    public ResponseEntity<List<SystemContracts.OperationEvent>> recent() {
        return ok(service.recent());
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(body);
    }
}
