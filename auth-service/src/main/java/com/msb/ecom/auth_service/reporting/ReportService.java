package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.reporting.ReportContracts.Assignment;
import com.msb.ecom.auth_service.reporting.ReportContracts.Capabilities;
import com.msb.ecom.auth_service.reporting.ReportContracts.CreateReportRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.Detail;
import com.msb.ecom.auth_service.reporting.ReportContracts.DismissRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.EnforcementSummary;
import com.msb.ecom.auth_service.reporting.ReportContracts.ListingTargetContext;
import com.msb.ecom.auth_service.reporting.ReportContracts.Page;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReadyRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReasonCode;
import com.msb.ecom.auth_service.reporting.ReportContracts.RelatedReport;
import com.msb.ecom.auth_service.reporting.ReportContracts.Severity;
import com.msb.ecom.auth_service.reporting.ReportContracts.SeverityRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.Status;
import com.msb.ecom.auth_service.reporting.ReportContracts.SubmissionResult;
import com.msb.ecom.auth_service.reporting.ReportContracts.Summary;
import com.msb.ecom.auth_service.reporting.ReportContracts.TimelineEntry;
import com.msb.ecom.auth_service.reporting.ReportContracts.VersionRequest;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportService {
    private static final int MAX_DESCRIPTION = 2000;
    private static final int MAX_TRIAGE_REASON = 1000;
    private static final int HOURLY_LIMIT = 5;
    private static final int DAILY_LIMIT = 20;
    private static final Map<ReportContracts.TargetType, Set<ReasonCode>> REASONS = reasons();

    private final ReportRepository repository;
    private final InvestigationCaseRepository investigationCases;
    private final ProductReportTargetClient productTargets;
    private final AuthService authService;
    private final AdminAuthorizationService adminAuthorization;
    private final EnforcementService enforcementService;
    private final UlidGenerator ulids;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    // Creates an allegation only; this path never invokes an enforcement mutation.
    public SubmissionResult submit(CreateReportRequest request, String correlationId) {
        User reporter = authService.ensureUserEntity();
        NormalizedSubmission normalized = normalize(request);
        TargetSnapshot target = targetSnapshot(normalized.targetType(), normalized.targetId(), reporter.getId(), true);
        Instant now = clock.instant();
        enforceRate(reporter.getId(), now);

        String reportId = ulids.next();
        Severity severity = initialSeverity(normalized.reasonCode());
        ReportRepository.ReportRow row = new ReportRepository.ReportRow(reportId, reporter.getId(),
                normalized.targetType(), normalized.targetId(), target.label(), normalized.reasonCode(),
                normalized.description(), severity, Status.SUBMITTED, null, target.snapshot(), Map.of(), 0,
                null, null, null, null, correlationId, now, now);
        repository.insert(row);
        if (!repository.reserveDuplicate(reporter.getId(), normalized.targetType(), normalized.targetId(),
                normalized.reasonCode(), reportId, now, now.plus(24, ChronoUnit.HOURS))) {
            throw ReportException.conflict("REPORT_ALREADY_SUBMITTED",
                    "This report was already submitted during the 24-hour duplicate window.");
        }
        repository.insertEvent(event(row, "REPORT_SUBMITTED", "MARKETPLACE_USER", reporter.getId(),
                safeName(reporter), "MARKETPLACE", null, Status.SUBMITTED.name(), normalized.reasonCode().name(),
                normalized.description(), correlationId));
        return new SubmissionResult(reportId, Status.SUBMITTED, now, "RPT-" + reportId.substring(reportId.length() - 8));
    }

    @Transactional(readOnly = true)
    public Page search(String query, Status status, ReportContracts.TargetType targetType, ReasonCode reasonCode,
                       Severity severity, Assignment assignment, Boolean unresolved,
                       Instant createdFrom, Instant createdTo,
                       int page, int size, String sort) {
        User admin = authService.ensureUserEntity();
        adminAuthorization.requirePermission(admin, AdminPermission.REPORT_READ);
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        String normalizedSort = normalizedSort(sort);
        String q = query == null || query.isBlank() ? null : FixedLengthIds.requireTrimmed("Search ID", query, 26);
        Assignment normalizedAssignment = assignment == null ? Assignment.ALL : assignment;
        ReportRepository.SearchResult result = repository.search(new ReportRepository.Search(q, status, targetType,
                reasonCode, severity, normalizedAssignment, Boolean.TRUE.equals(unresolved),
                createdFrom, createdTo, normalizedPage, normalizedSize, normalizedSort, admin.getId()));
        List<Summary> items = result.items().stream()
                .map(value -> summary(value.report(), value.relatedReportCount())).toList();
        int totalPages = (int) Math.ceil((double) result.total() / normalizedSize);
        return new Page(items, normalizedPage, normalizedSize, result.total(), totalPages, normalizedSort);
    }

    @Transactional(readOnly = true)
    public Detail detail(String reportId) {
        User admin = authService.ensureUserEntity();
        adminAuthorization.requirePermission(admin, AdminPermission.REPORT_READ);
        ReportRepository.ReportRow row = report(reportId);
        return detail(row, admin);
    }

    @Transactional
    public Detail claim(String reportId, VersionRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_ASSIGN);
        ReportRepository.ReportRow before = report(reportId);
        long version = requiredVersion(request == null ? null : request.expectedVersion());
        if (!repository.claim(before.id(), version, admin.getId(), clock.instant())) mutationConflict(before, version, admin.getId());
        return afterMutation(before, admin, "REPORT_CLAIMED", before.status().name(), Status.UNDER_TRIAGE.name(),
                null, null, correlationId);
    }

    @Transactional
    public Detail release(String reportId, VersionRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_ASSIGN);
        ReportRepository.ReportRow before = report(reportId);
        long version = requiredVersion(request == null ? null : request.expectedVersion());
        if (!repository.release(before.id(), version, admin.getId(), clock.instant())) mutationConflict(before, version, admin.getId());
        return afterMutation(before, admin, "REPORT_RELEASED", before.status().name(), Status.SUBMITTED.name(),
                null, null, correlationId);
    }

    @Transactional
    public Detail severity(String reportId, SeverityRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null || request.severity() == null) throw ReportException.invalid("REPORT_INVALID_SEVERITY", "Severity is required.");
        String reason = requiredReason(request.reason());
        ReportRepository.ReportRow before = report(reportId);
        long version = requiredVersion(request.expectedVersion());
        if (!repository.severity(before.id(), version, admin.getId(), request.severity(), clock.instant())) mutationConflict(before, version, admin.getId());
        return afterMutation(before, admin, "REPORT_SEVERITY_CHANGED", before.severity().name(), request.severity().name(),
                "SEVERITY_REASSESSMENT", reason, correlationId);
    }

    @Transactional
    public Detail dismiss(String reportId, DismissRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null || request.reasonCode() == null) throw ReportException.invalid("REPORT_DISMISSAL_REASON_REQUIRED", "Dismissal reason code is required.");
        String reason = requiredReason(request.reason());
        ReportRepository.ReportRow before = report(reportId);
        long version = requiredVersion(request.expectedVersion());
        if (!repository.resolve(before.id(), version, admin.getId(), Status.DISMISSED,
                request.reasonCode().name(), reason, clock.instant())) mutationConflict(before, version, admin.getId());
        return afterMutation(before, admin, "REPORT_DISMISSED", before.status().name(), Status.DISMISSED.name(),
                request.reasonCode().name(), reason, correlationId);
    }

    @Transactional
    public Detail ready(String reportId, ReadyRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        String reason = requiredReason(request == null ? null : request.reason());
        ReportRepository.ReportRow before = report(reportId);
        long version = requiredVersion(request == null ? null : request.expectedVersion());
        if (!repository.resolve(before.id(), version, admin.getId(), Status.READY_FOR_INVESTIGATION,
                "REQUIRES_INVESTIGATION", reason, clock.instant())) mutationConflict(before, version, admin.getId());
        return afterMutation(before, admin, "REPORT_MARKED_READY_FOR_INVESTIGATION", before.status().name(),
                Status.READY_FOR_INVESTIGATION.name(), "REQUIRES_INVESTIGATION", reason, correlationId);
    }

    private Detail afterMutation(ReportRepository.ReportRow before, User actor, String eventType,
                                 String previous, String next, String reasonCode, String reason, String correlationId) {
        ReportRepository.ReportRow after = report(before.id());
        repository.insertEvent(event(after, eventType, "PLATFORM_ADMIN", actor.getId(), safeName(actor),
                "HUMAN_ADMIN", previous, next, reasonCode, reason, correlationId));
        return detail(after, actor);
    }

    private Detail detail(ReportRepository.ReportRow row, User admin) {
        var access = adminAuthorization.accessFor(admin);
        List<RelatedReport> related = repository.related(row.targetType(), row.targetId(), row.id(), 20).stream()
                .map(value -> new RelatedReport(value.id(), value.reasonCode(), value.severity(), value.status(), value.createdAt())).toList();
        List<TimelineEntry> timeline = repository.events(row.id()).stream().map(value -> new TimelineEntry(
                value.eventId(), value.occurredAt(), value.eventType(), value.actorType(), value.actorId(),
                value.actorDisplayName(), value.source(), value.previousState(), value.newState(), value.reasonCode(),
                value.reason(), value.correlationId(), value.requestId(), value.safeMetadata())).toList();
        ReportContracts.AdminSummary assignee = row.assignedAdminId() == null ? null
                : new ReportContracts.AdminSummary(row.assignedAdminId(), repository.safeUserLabel(row.assignedAdminId()));
        return new Detail(row.id(), new ReportContracts.ReporterSummary(row.reporterUserId(), repository.safeUserLabel(row.reporterUserId())),
                row.targetType(), row.targetId(), row.safeTargetLabel(), row.reasonCode(), row.description(), row.severity(),
                row.status(), assignee, row.createdAt(), row.updatedAt(), row.version(), row.targetSnapshot(),
                currentTarget(row), related, currentEnforcement(row), timeline, capabilities(row, admin.getId(), access),
                investigationCase(row.id()));
    }

    private ReportContracts.InvestigationCaseLink investigationCase(String reportId) {
        return investigationCases.caseIdForReport(reportId).flatMap(investigationCases::find)
                .map(value -> new ReportContracts.InvestigationCaseLink(
                        value.id(), value.title(), value.status().name())).orElse(null);
    }

    private JsonNode currentTarget(ReportRepository.ReportRow row) {
        try {
            return switch (row.targetType()) {
                case USER -> repository.safeUser(row.targetId()).<JsonNode>map(value -> mapper.valueToTree(Map.of(
                        "available", true, "userId", value.id(), "safeDisplayName", value.displayName(),
                        "accountState", value.status(), "version", value.version(), "capturedAt", clock.instant().toString())))
                        .orElseGet(() -> mapper.valueToTree(Map.of("available", false, "state", "UNAVAILABLE")));
                case BUSINESS -> repository.safeBusiness(row.targetId()).<JsonNode>map(value -> mapper.valueToTree(Map.of(
                        "available", true, "businessId", value.id(), "displayName", value.displayName(),
                        "businessState", value.status(), "storeState", value.storeStatus() == null ? "UNAVAILABLE" : value.storeStatus(),
                        "version", value.version(), "capturedAt", clock.instant().toString())))
                        .orElseGet(() -> mapper.valueToTree(Map.of("available", false, "state", "UNAVAILABLE")));
                case LISTING -> productTargets.listing(row.targetId()).<JsonNode>map(mapper::valueToTree)
                        .orElseGet(() -> mapper.valueToTree(Map.of("available", false, "state", "UNAVAILABLE")));
                default -> mapper.valueToTree(Map.of("available", false, "state", "NOT_OPERATIONAL"));
            };
        } catch (ReportException exception) {
            return mapper.valueToTree(Map.of("available", false, "state", "TEMPORARILY_UNAVAILABLE"));
        }
    }

    private List<EnforcementSummary> currentEnforcement(ReportRepository.ReportRow row) {
        if (row.targetType() == ReportContracts.TargetType.LISTING) {
            try { return productTargets.listing(row.targetId()).map(ListingTargetContext::enforcement).orElse(List.of()); }
            catch (ReportException ignored) { return List.of(); }
        }
        TargetType type = row.targetType() == ReportContracts.TargetType.USER ? TargetType.USER : TargetType.BUSINESS;
        return enforcementService.evaluate(type, row.targetId(), clock.instant()).stream().map(this::enforcement).toList();
    }

    private EnforcementSummary enforcement(EffectiveRestriction value) {
        return new EnforcementSummary(value.actionType().name(), List.of(value.scope().name()));
    }

    private Capabilities capabilities(ReportRepository.ReportRow row, String adminId,
                                      AdminAuthorizationService.AdminAccessSnapshot access) {
        boolean resolved = row.status() == Status.DISMISSED || row.status() == Status.READY_FOR_INVESTIGATION
                || row.status() == Status.LINKED_TO_CASE;
        boolean mine = adminId.equals(row.assignedAdminId());
        boolean other = row.assignedAdminId() != null && !mine;
        boolean canAssign = access.has(AdminPermission.REPORT_ASSIGN);
        boolean canResolve = access.has(AdminPermission.REPORT_RESOLVE);
        boolean canClaim = canAssign && row.status() == Status.SUBMITTED && row.assignedAdminId() == null;
        boolean canRelease = canAssign && row.status() == Status.UNDER_TRIAGE && mine;
        boolean canTriage = canResolve && row.status() == Status.UNDER_TRIAGE && mine;
        String reason = resolved ? "This report has completed triage." : other ? "This report is assigned to another admin."
                : row.assignedAdminId() == null ? "Claim this report before triage." : !canResolve ? "You do not have report resolution permission." : null;
        boolean canInvestigate = access.has(AdminPermission.REPORT_INVESTIGATE);
        boolean readyForCase = row.status() == Status.READY_FOR_INVESTIGATION;
        boolean linked = row.status() == Status.LINKED_TO_CASE;
        return new Capabilities(true, canClaim, canRelease, canTriage, canTriage, canTriage, mine, other,
                resolved, !canClaim && !canRelease && !canTriage && !readyForCase && !linked, reason,
                canInvestigate && readyForCase, canInvestigate && readyForCase, linked);
    }

    private TargetSnapshot targetSnapshot(ReportContracts.TargetType type, String targetId, String reporterId, boolean requireReportable) {
        Instant capturedAt = clock.instant();
        return switch (type) {
            case USER -> {
                ReportRepository.SafeUser user = repository.safeUser(targetId)
                        .filter(value -> "HUMAN".equals(value.accountType()))
                        .filter(value -> !requireReportable || "ACTIVE".equals(value.status()))
                        .orElseThrow(ReportException::invalidTarget);
                if (reporterId.equals(user.id())) throw ReportException.invalid("REPORT_SELF_TARGET_NOT_ALLOWED", "You cannot report yourself.");
                yield new TargetSnapshot(user.displayName(), mapper.valueToTree(Map.of("userId", user.id(),
                        "safeDisplayName", user.displayName(), "accountState", user.status(),
                        "capturedAt", capturedAt.toString(), "version", user.version())));
            }
            case BUSINESS -> {
                ReportRepository.SafeBusiness business = repository.safeBusiness(targetId)
                        .filter(value -> !requireReportable || ("ACTIVE".equals(value.status()) && "ACTIVE".equals(value.storeStatus())))
                        .orElseThrow(ReportException::invalidTarget);
                if (repository.isActiveBusinessMember(targetId, reporterId))
                    throw ReportException.invalid("REPORT_SELF_TARGET_NOT_ALLOWED", "You cannot report a business you manage.");
                Map<String, Object> values = new java.util.LinkedHashMap<>();
                values.put("businessId", business.id()); values.put("displayName", business.displayName());
                values.put("businessState", business.status()); values.put("ownerUserId", business.ownerUserId());
                values.put("capturedAt", capturedAt.toString()); values.put("version", business.version());
                yield new TargetSnapshot(business.displayName(), mapper.valueToTree(values));
            }
            case LISTING -> {
                ListingTargetContext listing = productTargets.listing(targetId).filter(value -> !requireReportable || value.reportable())
                        .orElseThrow(ReportException::invalidTarget);
                if (reporterId.equals(listing.individualSellerUserId()) ||
                        (listing.businessId() != null && repository.isActiveBusinessMember(listing.businessId(), reporterId)))
                    throw ReportException.invalid("REPORT_SELF_TARGET_NOT_ALLOWED", "You cannot report your own listing.");
                yield new TargetSnapshot(listing.title(), mapper.valueToTree(listing));
            }
            default -> throw ReportException.invalid("REPORT_INVALID_TARGET", "This target type is not operational.");
        };
    }

    private NormalizedSubmission normalize(CreateReportRequest request) {
        if (request == null || request.targetType() == null || request.reasonCode() == null)
            throw ReportException.invalid("REPORT_INVALID_REQUEST", "Target type, target ID, and reason are required.");
        if (!Set.of(ReportContracts.TargetType.USER, ReportContracts.TargetType.BUSINESS, ReportContracts.TargetType.LISTING).contains(request.targetType()))
            throw ReportException.invalid("REPORT_INVALID_TARGET", "This target type is not operational.");
        String targetId = FixedLengthIds.requireTrimmed("Target ID", request.targetId(), 26);
        if (!REASONS.get(request.targetType()).contains(request.reasonCode()))
            throw ReportException.invalid("REPORT_INVALID_REASON", "The selected reason is not valid for this target.");
        String description = normalizeText(request.description(), MAX_DESCRIPTION);
        if (request.reasonCode() == ReasonCode.OTHER && description == null)
            throw ReportException.invalid("REPORT_DESCRIPTION_REQUIRED", "An explanation is required for Other.");
        return new NormalizedSubmission(request.targetType(), targetId, request.reasonCode(), description);
    }

    private void enforceRate(String reporterId, Instant now) {
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        Instant day = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        if (repository.incrementRate(reporterId, "HOUR", hour, hour.plus(1, ChronoUnit.HOURS)) > HOURLY_LIMIT ||
                repository.incrementRate(reporterId, "DAY", day, day.plus(1, ChronoUnit.DAYS)) > DAILY_LIMIT)
            throw ReportException.rateLimited();
    }

    private ReportRepository.EventRow event(ReportRepository.ReportRow report, String type, String actorType,
                                             String actorId, String actorName, String source, String previous,
                                             String next, String reasonCode, String reason, String correlationId) {
        return new ReportRepository.EventRow(ulids.next(), report.id(), type, clock.instant(), actorType, actorId,
                actorName, source, previous, next, reasonCode, reason, correlationId, ulids.next(), Map.of());
    }

    private Summary summary(ReportRepository.ReportRow row, long relatedReportCount) {
        return new Summary(row.id(), row.targetType(), row.targetId(), row.safeTargetLabel(), row.reasonCode(),
                row.severity(), row.status(), row.reporterUserId(), row.assignedAdminId(), row.createdAt(), row.updatedAt(),
                row.version(), relatedReportCount);
    }

    private ReportRepository.ReportRow report(String id) {
        return repository.find(FixedLengthIds.requireTrimmed("Report ID", id, 26)).orElseThrow(ReportException::notFound);
    }

    private User authorized(AdminPermission permission) {
        User user = authService.ensureUserEntity();
        adminAuthorization.requirePermission(user, permission);
        return user;
    }

    private void mutationConflict(ReportRepository.ReportRow before, long expected, String adminId) {
        ReportRepository.ReportRow current = repository.find(before.id()).orElseThrow(ReportException::notFound);
        if (current.version() != expected) throw ReportException.conflict("REPORT_VERSION_CONFLICT", "The report changed. Refresh and try again.");
        if (current.status() == Status.DISMISSED || current.status() == Status.READY_FOR_INVESTIGATION
                || current.status() == Status.LINKED_TO_CASE)
            throw ReportException.conflict("REPORT_ALREADY_RESOLVED", "The report has completed triage.");
        if (current.assignedAdminId() != null && !adminId.equals(current.assignedAdminId()))
            throw ReportException.conflict("REPORT_NOT_ASSIGNED_TO_CURRENT_ADMIN", "The report is assigned to another admin.");
        throw ReportException.conflict("REPORT_NOT_ASSIGNABLE", "The report is not valid for this action.");
    }

    private long requiredVersion(Long value) {
        if (value == null || value < 0) throw ReportException.invalid("REPORT_VERSION_REQUIRED", "The current report version is required.");
        return value;
    }

    private String requiredReason(String value) {
        String normalized = normalizeText(value, MAX_TRIAGE_REASON);
        if (normalized == null) throw ReportException.invalid("REPORT_TRIAGE_REASON_REQUIRED", "A triage reason is required.");
        return normalized;
    }

    private String normalizeText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > max) throw ReportException.invalid("REPORT_DESCRIPTION_TOO_LONG", "Text is longer than the allowed limit.");
        if (normalized.chars().anyMatch(ch -> Character.isISOControl(ch)))
            throw ReportException.invalid("REPORT_INVALID_TEXT", "Text contains unsupported control characters.");
        return normalized;
    }

    private Severity initialSeverity(ReasonCode reason) {
        return switch (reason) {
            case SPAM -> Severity.LOW;
            case PROHIBITED_ITEM, HARASSMENT -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
    }

    private String normalizedSort(String value) {
        String sort = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
        if (!Set.of("createdAt,desc", "createdAt,asc", "updatedAt,desc", "severity,desc").contains(sort))
            throw ReportException.invalid("REPORT_INVALID_SORT", "Sort is invalid.");
        return sort;
    }

    private String safeName(User user) { return user.getDisplayName() == null || user.getDisplayName().isBlank() ? "Marketplace user" : user.getDisplayName().trim(); }

    private static Map<ReportContracts.TargetType, Set<ReasonCode>> reasons() {
        Map<ReportContracts.TargetType, Set<ReasonCode>> values = new EnumMap<>(ReportContracts.TargetType.class);
        values.put(ReportContracts.TargetType.LISTING, Set.of(ReasonCode.SCAM, ReasonCode.COUNTERFEIT,
                ReasonCode.PROHIBITED_ITEM, ReasonCode.MISLEADING_LISTING, ReasonCode.SPAM, ReasonCode.OTHER));
        values.put(ReportContracts.TargetType.USER, Set.of(ReasonCode.SCAM, ReasonCode.HARASSMENT,
                ReasonCode.SPAM, ReasonCode.IMPERSONATION, ReasonCode.OTHER));
        values.put(ReportContracts.TargetType.BUSINESS, Set.of(ReasonCode.SCAM, ReasonCode.COUNTERFEIT,
                ReasonCode.HARASSMENT, ReasonCode.SPAM, ReasonCode.IMPERSONATION, ReasonCode.OTHER));
        return Map.copyOf(values);
    }

    private record TargetSnapshot(String label, JsonNode snapshot) { }
    private record NormalizedSubmission(ReportContracts.TargetType targetType, String targetId,
                                        ReasonCode reasonCode, String description) { }
}
