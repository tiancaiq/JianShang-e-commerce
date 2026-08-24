package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;

@Service
@RequiredArgsConstructor
public class InvestigationCaseService {
    private static final int MAX_TITLE = 160;
    private static final int MAX_REASON = 1000;
    private static final int MAX_NOTE = 4000;
    private static final int MAX_LABEL = 200;
    private static final Set<Status> INVESTIGABLE = Set.of(Status.OPEN, Status.UNDER_INVESTIGATION);

    private final InvestigationCaseRepository cases;
    private final ReportRepository reports;
    private final ProductReportTargetClient productTargets;
    private final AuthService authService;
    private final AdminAuthorizationService authorization;
    private final EnforcementService enforcementService;
    private final CaseEnforcementService caseEnforcement;
    private final UlidGenerator ulids;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    // Creates the investigation and links the allegation in one Auth-owned transaction; no enforcement API is invoked.
    public Detail createFromReport(String rawReportId, CreateRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null) throw invalid("INVESTIGATION_CASE_INVALID_REQUEST", "Case details are required.");
        String reportId = id("Report ID", rawReportId);
        ReportRepository.ReportRow report = report(reportId);
        long expectedReportVersion = version(request.expectedReportVersion(), "report");
        if (report.version() != expectedReportVersion) throw reportVersionConflict();
        if (report.status() != ReportContracts.Status.READY_FOR_INVESTIGATION) {
            if (cases.caseIdForReport(reportId).isPresent()) {
                throw conflict("REPORT_ALREADY_LINKED_TO_CASE", "The report already belongs to an investigation case.");
            }
            throw conflict("REPORT_NOT_READY_FOR_INVESTIGATION", "Only reports ready for investigation can create a case.");
        }
        String title = requiredText(request.title(), MAX_TITLE, "Case title");
        Instant now = clock.instant();
        String caseId = ulids.next();
        ReportContracts.Severity severity = request.severity() == null ? report.severity() : request.severity();
        cases.insertCase(new InvestigationCaseRepository.CaseRow(caseId, title, Status.OPEN, severity,
                report.targetType(), report.targetId(), report.safeTargetLabel(), null, admin.getId(),
                null, null, null, null, correlationId, 0, now, now, 1, 1, 0));
        cases.insertTarget(caseId, report.targetType(), report.targetId(), report.safeTargetLabel(),
                RelationshipType.PRIMARY, admin.getId(), now);
        cases.insertReportLink(caseId, reportId, admin.getId(), now);
        if (!cases.markReportLinked(reportId, expectedReportVersion, now)) throw reportVersionConflict();

        cases.insertEvent(event(caseId, "CASE_CREATED", admin, null, Status.OPEN.name(), null,
                "Created from report " + reportId, correlationId, Map.of("reportId", reportId)));
        cases.insertEvent(event(caseId, "REPORT_LINKED", admin, ReportContracts.Status.READY_FOR_INVESTIGATION.name(),
                ReportContracts.Status.LINKED_TO_CASE.name(), null, "Primary report linked", correlationId,
                Map.of("reportId", reportId)));
        reports.insertEvent(reportEvent(reportId, report, admin, "REPORT_LINKED_TO_CASE",
                ReportContracts.Status.READY_FOR_INVESTIGATION, ReportContracts.Status.LINKED_TO_CASE,
                "INVESTIGATION_CASE_CREATED", "Linked to investigation case " + caseId, correlationId,
                Map.of("caseId", caseId)));
        return detail(caseId, admin);
    }

    @Transactional(readOnly = true)
    public Page search(String query, Status status, ReportContracts.Severity severity,
                       ReportContracts.TargetType targetType, Assignment assignment,
                       Instant createdFrom, Instant createdTo, Instant updatedFrom, Instant updatedTo,
                       int page, int size, String sort) {
        User admin = authorized(AdminPermission.REPORT_READ);
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        String q = optionalText(query, MAX_TITLE, "Search");
        String normalizedSort = normalizedSort(sort);
        InvestigationCaseRepository.SearchResult result = cases.search(new InvestigationCaseRepository.Search(
                q, operationalStatus(status), severity, targetType == null ? null : operationalTarget(targetType),
                assignment == null ? Assignment.ALL : assignment, createdFrom, createdTo, updatedFrom, updatedTo,
                normalizedPage, normalizedSize, normalizedSort, admin.getId()));
        List<Summary> items = result.rows().stream().map(this::summary).toList();
        int totalPages = (int) Math.ceil((double) result.total() / normalizedSize);
        return new Page(items, normalizedPage, normalizedSize, result.total(), totalPages, normalizedSort);
    }

    @Transactional(readOnly = true)
    public Detail detail(String rawCaseId) {
        User admin = authorized(AdminPermission.REPORT_READ);
        return detail(id("Case ID", rawCaseId), admin);
    }

    @Transactional
    public Detail claim(String rawCaseId, VersionRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_ASSIGN);
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request == null ? null : request.expectedVersion(), "case");
        if (!cases.claim(before.id(), expected, admin.getId(), clock.instant())) mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "CASE_CLAIMED", admin, "UNASSIGNED", admin.getId(), null,
                "Case claimed", correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail release(String rawCaseId, VersionRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_ASSIGN);
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request == null ? null : request.expectedVersion(), "case");
        if (!cases.release(before.id(), expected, admin.getId(), clock.instant())) mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "CASE_RELEASED", admin, admin.getId(), "UNASSIGNED", null,
                "Case released", correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail start(String rawCaseId, ReasonRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request == null ? null : request.expectedVersion(), "case");
        String reason = requiredText(request == null ? null : request.reason(), MAX_REASON, "Investigation reason");
        if (!cases.start(before.id(), expected, admin.getId(), clock.instant())) mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "INVESTIGATION_STARTED", admin, Status.OPEN.name(),
                Status.UNDER_INVESTIGATION.name(), null, reason, correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail linkReport(String rawCaseId, LinkReportRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null) throw invalid("INVESTIGATION_CASE_INVALID_REQUEST", "Report link details are required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        String reportId = id("Report ID", request.reportId());
        long caseVersion = version(request.expectedCaseVersion(), "case");
        long reportVersion = version(request.expectedReportVersion(), "report");
        ReportRepository.ReportRow report = report(reportId);
        var existingCase = cases.caseIdForReport(reportId);
        if (existingCase.isPresent()) {
            if (existingCase.get().equals(before.id()) && before.version() == caseVersion
                    && report.version() == reportVersion) return detail(before.id(), admin);
            throw conflict("REPORT_ALREADY_LINKED_TO_CASE", "The report already belongs to an investigation case.");
        }
        if (report.version() != reportVersion) throw reportVersionConflict();
        if (report.status() != ReportContracts.Status.READY_FOR_INVESTIGATION)
            throw conflict("REPORT_NOT_READY_FOR_INVESTIGATION", "Only reports ready for investigation can be linked.");
        Instant now = clock.instant();
        if (!cases.touchInvestigableOwned(before.id(), caseVersion, admin.getId(), now))
            mutationConflict(before.id(), caseVersion, admin);
        cases.insertReportLink(before.id(), reportId, admin.getId(), now);
        if (!cases.markReportLinked(reportId, reportVersion, now)) throw reportVersionConflict();
        cases.insertEvent(event(before.id(), "REPORT_LINKED", admin,
                ReportContracts.Status.READY_FOR_INVESTIGATION.name(), ReportContracts.Status.LINKED_TO_CASE.name(),
                null, "Report linked", correlationId, Map.of("reportId", reportId)));
        reports.insertEvent(reportEvent(reportId, report, admin, "REPORT_LINKED_TO_CASE",
                ReportContracts.Status.READY_FOR_INVESTIGATION, ReportContracts.Status.LINKED_TO_CASE,
                "INVESTIGATION_CASE_LINK", "Linked to investigation case " + before.id(), correlationId,
                Map.of("caseId", before.id())));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail unlinkReport(String rawCaseId, String rawReportId, UnlinkReportRequest request,
                               String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null) throw invalid("INVESTIGATION_CASE_INVALID_REQUEST", "Report unlink details are required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        String reportId = id("Report ID", rawReportId);
        long caseVersion = version(request.expectedCaseVersion(), "case");
        long reportVersion = version(request.expectedReportVersion(), "report");
        String reason = requiredText(request.reason(), MAX_REASON, "Unlink reason");
        ReportRepository.ReportRow report = report(reportId);
        if (!cases.caseIdForReport(reportId).filter(before.id()::equals).isPresent())
            throw conflict("REPORT_NOT_LINKED_TO_CASE", "The report is not linked to this case.");
        if (report.version() != reportVersion) throw reportVersionConflict();
        Instant now = clock.instant();
        if (!cases.touchInvestigableOwned(before.id(), caseVersion, admin.getId(), now))
            mutationConflict(before.id(), caseVersion, admin);
        if (!cases.restoreReportReady(reportId, reportVersion, now)) throw reportVersionConflict();
        if (!cases.deleteReportLink(before.id(), reportId))
            throw conflict("REPORT_NOT_LINKED_TO_CASE", "The report link changed. Refresh and try again.");
        cases.insertEvent(event(before.id(), "REPORT_UNLINKED", admin,
                ReportContracts.Status.LINKED_TO_CASE.name(), ReportContracts.Status.READY_FOR_INVESTIGATION.name(),
                null, reason, correlationId, Map.of("reportId", reportId)));
        reports.insertEvent(reportEvent(reportId, report, admin, "REPORT_UNLINKED_FROM_CASE",
                ReportContracts.Status.LINKED_TO_CASE, ReportContracts.Status.READY_FOR_INVESTIGATION,
                "INVESTIGATION_CASE_UNLINK", reason, correlationId, Map.of("caseId", before.id())));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail linkTarget(String rawCaseId, LinkTargetRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null || request.targetType() == null)
            throw invalid("INVESTIGATION_TARGET_REQUIRED", "Target type and ID are required.");
        if (request.relationshipType() != null && request.relationshipType() != RelationshipType.RELATED)
            throw invalid("INVESTIGATION_TARGET_RELATIONSHIP_INVALID", "Additional targets must be related targets.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request.expectedCaseVersion(), "case");
        ReportContracts.TargetType type = operationalTarget(request.targetType());
        String targetId = id("Target ID", request.targetId());
        if (cases.hasTarget(before.id(), type, targetId))
            throw conflict("INVESTIGATION_TARGET_ALREADY_LINKED", "The target is already linked to this case.");
        TargetContext target = target(type, targetId);
        Instant now = clock.instant();
        if (!cases.touchInvestigableOwned(before.id(), expected, admin.getId(), now))
            mutationConflict(before.id(), expected, admin);
        cases.insertTarget(before.id(), type, targetId, target.label(), RelationshipType.RELATED, admin.getId(), now);
        cases.insertEvent(event(before.id(), "TARGET_LINKED", admin, "UNLINKED", "RELATED", null,
                "Related target linked", correlationId, Map.of("targetType", type.name(), "targetId", targetId)));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail unlinkTarget(String rawCaseId, ReportContracts.TargetType type, String rawTargetId,
                               UnlinkTargetRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request == null ? null : request.expectedCaseVersion(), "case");
        String reason = requiredText(request == null ? null : request.reason(), MAX_REASON, "Unlink reason");
        ReportContracts.TargetType targetType = operationalTarget(type);
        String targetId = id("Target ID", rawTargetId);
        if (before.primaryTargetType() == targetType && before.primaryTargetId().equals(targetId))
            throw conflict("INVESTIGATION_PRIMARY_TARGET_REQUIRED", "The primary target cannot be removed.");
        Instant now = clock.instant();
        if (!cases.touchInvestigableOwned(before.id(), expected, admin.getId(), now))
            mutationConflict(before.id(), expected, admin);
        if (!cases.deleteRelatedTarget(before.id(), targetType, targetId))
            throw conflict("INVESTIGATION_TARGET_NOT_LINKED", "The related target is not linked to this case.");
        cases.insertEvent(event(before.id(), "TARGET_UNLINKED", admin, "RELATED", "UNLINKED", null,
                reason, correlationId, Map.of("targetType", targetType.name(), "targetId", targetId)));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail addNote(String rawCaseId, AddNoteRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null) throw invalid("INVESTIGATION_NOTE_REQUIRED", "Internal note details are required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request.expectedVersion(), "case");
        String body = requiredText(request.body(), MAX_NOTE, "Internal note");
        String retry = requiredText(request.idempotencyKey(), 128, "Idempotency key");
        var existing = cases.noteByRetry(before.id(), admin.getId(), retry);
        if (existing.isPresent()) {
            if (!existing.get().body().equals(body))
                throw conflict("INVESTIGATION_NOTE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was already used for a different note.");
            return detail(before.id(), admin);
        }
        requireMutableOwned(before, expected, admin);
        Instant now = clock.instant();
        if (!cases.insertNote(new InvestigationCaseRepository.NoteInsert(ulids.next(), before.id(), body,
                admin.getId(), safeName(admin), correlationId, retry, now))) {
            var replay = cases.noteByRetry(before.id(), admin.getId(), retry);
            if (replay.isPresent() && replay.get().body().equals(body)) return detail(before.id(), admin);
            throw conflict("INVESTIGATION_NOTE_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different note.");
        }
        if (!cases.touchInvestigableOwned(before.id(), expected, admin.getId(), now))
            mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "NOTE_ADDED", admin, "NO_NOTE", "NOTE_ADDED", null,
                null, correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail addEvidence(String rawCaseId, AddEvidenceRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null || request.evidenceType() == null || request.referenceType() == null)
            throw invalid("INVESTIGATION_EVIDENCE_REQUIRED", "Evidence type and reference are required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request.expectedVersion(), "case");
        String referenceId = requiredText(request.referenceId(), 128, "Evidence reference");
        String label = requiredText(request.label(), MAX_LABEL, "Evidence label");
        JsonNode metadata = evidenceMetadata(before.id(), request.evidenceType(), request.referenceType(), referenceId);
        Instant now = clock.instant();
        if (!cases.touchInvestigableOwned(before.id(), expected, admin.getId(), now))
            mutationConflict(before.id(), expected, admin);
        if (!cases.insertEvidence(new InvestigationCaseRepository.EvidenceInsert(ulids.next(), before.id(),
                request.evidenceType(), request.referenceType(), referenceId, label, metadata, admin.getId(),
                correlationId, now))) {
            throw conflict("INVESTIGATION_EVIDENCE_ALREADY_LINKED", "That evidence reference is already linked.");
        }
        cases.insertEvent(event(before.id(), "EVIDENCE_ADDED", admin, "UNLINKED", "EVIDENCE_ADDED", null,
                null, correlationId, Map.of("evidenceType", request.evidenceType().name(),
                        "referenceId", referenceId)));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail severity(String rawCaseId, SeverityRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_INVESTIGATE);
        if (request == null || request.severity() == null)
            throw invalid("INVESTIGATION_SEVERITY_REQUIRED", "Severity is required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request.expectedVersion(), "case");
        String reason = requiredText(request.reason(), MAX_REASON, "Severity reason");
        if (!cases.severity(before.id(), expected, admin.getId(), request.severity(), clock.instant()))
            mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "SEVERITY_CHANGED", admin, before.severity().name(),
                request.severity().name(), "SEVERITY_REASSESSMENT", reason, correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail readyForAction(String rawCaseId, ReasonRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request == null ? null : request.expectedVersion(), "case");
        String reason = requiredText(request == null ? null : request.reason(), MAX_REASON, "Conclusion reason");
        if (before.reportCount() < 1)
            throw conflict("INVESTIGATION_REPORT_REQUIRED", "At least one linked report is required.");
        if (!cases.conclude(before.id(), expected, admin.getId(), Status.READY_FOR_ACTION,
                "ACTION_REVIEW_REQUIRED", reason, clock.instant())) mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "MARKED_READY_FOR_ACTION", admin, before.status().name(),
                Status.READY_FOR_ACTION.name(), "ACTION_REVIEW_REQUIRED", reason, correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    @Transactional
    public Detail closeNoAction(String rawCaseId, CloseRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null || request.conclusionCode() == null)
            throw invalid("INVESTIGATION_CONCLUSION_REQUIRED", "Conclusion code and reason are required.");
        InvestigationCaseRepository.CaseRow before = caseRow(rawCaseId);
        long expected = version(request.expectedVersion(), "case");
        String reason = requiredText(request.reason(), MAX_REASON, "Conclusion reason");
        if (!cases.conclude(before.id(), expected, admin.getId(), Status.CLOSED_NO_ACTION,
                request.conclusionCode().name(), reason, clock.instant())) mutationConflict(before.id(), expected, admin);
        cases.insertEvent(event(before.id(), "CLOSED_NO_ACTION", admin, before.status().name(),
                Status.CLOSED_NO_ACTION.name(), request.conclusionCode().name(), reason, correlationId, Map.of()));
        return detail(before.id(), admin);
    }

    private Detail detail(String caseId, User admin) {
        InvestigationCaseRepository.CaseRow row = cases.find(caseId).orElseThrow(InvestigationCaseException::notFound);
        List<Target> targets = cases.targets(caseId).stream().map(this::targetDetail).toList();
        Target primary = targets.stream().filter(value -> value.relationshipType() == RelationshipType.PRIMARY)
                .findFirst().orElseThrow(() -> new IllegalStateException("Investigation case has no primary target."));
        List<LinkedReport> linkedReports = cases.reports(caseId).stream().map(value -> new LinkedReport(
                value.reportId(), value.reasonCode(), value.severity(), value.status(), value.safeTargetLabel(),
                value.description(), value.targetSnapshot(), value.createdAt(), value.version())).toList();
        List<Note> notes = cases.notes(caseId).stream().map(value -> new Note(value.id(), value.body(),
                value.adminId(), value.adminName(), value.createdAt(), value.correlationId())).toList();
        List<Evidence> evidence = cases.evidence(caseId).stream().map(value -> new Evidence(value.id(),
                value.evidenceType(), value.referenceType(), value.referenceId(), value.label(),
                value.snapshotMetadata(), value.adminId(), value.createdAt())).toList();
        List<TimelineEntry> timeline = cases.events(caseId).stream().map(value -> new TimelineEntry(
                value.eventId(), value.occurredAt(), value.eventType(), "PLATFORM_ADMIN", value.actorId(),
                value.actorDisplayName(), "HUMAN_ADMIN", value.previousState(), value.newState(), value.reasonCode(),
                value.reason(), value.correlationId(), value.requestId(), value.safeMetadata())).toList();
        AdminSummary assignee = row.assignedAdminId() == null ? null
                : new AdminSummary(row.assignedAdminId(), reports.safeUserLabel(row.assignedAdminId()));
        CaseEnforcementService.Plan plan = caseEnforcement.plan(row, admin);
        return new Detail(row.id(), row.title(), row.status(), row.severity(), primary,
                targets.stream().filter(value -> value.relationshipType() == RelationshipType.RELATED).toList(),
                linkedReports, assignee, notes, evidence, plan.proposals(), plan.links(), timeline,
                row.conclusionCode(), row.conclusionReason(),
                row.readyForActionAt(), row.closedAt(), row.createdAt(), row.updatedAt(), row.version(),
                capabilities(row, admin, plan));
    }

    private Target targetDetail(InvestigationCaseRepository.TargetRow value) {
        TargetContext current;
        try { current = target(value.targetType(), value.targetId()); }
        catch (RuntimeException exception) {
            current = new TargetContext(value.safeTargetLabel(), mapper.valueToTree(Map.of(
                    "available", false, "state", "TEMPORARILY_UNAVAILABLE")), List.of());
        }
        return new Target(value.targetType(), value.targetId(), value.safeTargetLabel(), value.relationshipType(),
                value.linkedAt(), current.currentState(), current.enforcement(), adminPath(value.targetType(), value.targetId()));
    }

    private TargetContext target(ReportContracts.TargetType type, String targetId) {
        return switch (operationalTarget(type)) {
            case USER -> {
                var value = reports.safeUser(targetId).orElseThrow(() -> invalid(
                        "INVESTIGATION_TARGET_NOT_FOUND", "User target was not found."));
                JsonNode current = mapper.valueToTree(Map.of("available", true, "userId", value.id(),
                        "safeDisplayName", value.displayName(), "accountState", value.status(),
                        "version", value.version(), "capturedAt", clock.instant().toString()));
                yield new TargetContext(value.displayName(), current,
                        identityEnforcement(com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType.USER,
                                targetId));
            }
            case BUSINESS -> {
                var value = reports.safeBusiness(targetId).orElseThrow(() -> invalid(
                        "INVESTIGATION_TARGET_NOT_FOUND", "Business target was not found."));
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("available", true); state.put("businessId", value.id());
                state.put("displayName", value.displayName()); state.put("businessState", value.status());
                state.put("storeState", value.storeStatus() == null ? "UNAVAILABLE" : value.storeStatus());
                state.put("version", value.version()); state.put("capturedAt", clock.instant().toString());
                yield new TargetContext(value.displayName(), mapper.valueToTree(state),
                        identityEnforcement(com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType.BUSINESS,
                                targetId));
            }
            case LISTING -> {
                var value = productTargets.listing(targetId).orElseThrow(() -> invalid(
                        "INVESTIGATION_TARGET_NOT_FOUND", "Listing target was not found."));
                yield new TargetContext(value.title(), mapper.valueToTree(value), value.enforcement());
            }
            default -> throw invalid("INVESTIGATION_TARGET_TYPE_INVALID", "This target type is not operational.");
        };
    }

    private List<ReportContracts.EnforcementSummary> identityEnforcement(
            com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType type, String id) {
        return enforcementService.evaluate(type, id, clock.instant()).stream().map(this::enforcement).toList();
    }

    private ReportContracts.EnforcementSummary enforcement(EffectiveRestriction value) {
        return new ReportContracts.EnforcementSummary(value.actionType().name(), List.of(value.scope().name()));
    }

    private JsonNode evidenceMetadata(String caseId, EvidenceType evidenceType, ReferenceType referenceType,
                                      String referenceId) {
        if (evidenceType == EvidenceType.REPORT_SNAPSHOT) {
            if (referenceType != ReferenceType.REPORT)
                throw invalid("INVESTIGATION_EVIDENCE_REFERENCE_INVALID", "Report snapshots require a report reference.");
            InvestigationCaseRepository.ReportLinkRow report = cases.reports(caseId).stream()
                    .filter(value -> value.reportId().equals(referenceId)).findFirst()
                    .orElseThrow(() -> invalid("INVESTIGATION_EVIDENCE_REFERENCE_INVALID",
                            "The report is not linked to this case."));
            return mapper.valueToTree(Map.of("validated", true, "reportId", report.reportId(),
                    "snapshotStoredOnReport", true));
        }
        if (referenceType != ReferenceType.CASE_TARGET)
            throw invalid("INVESTIGATION_EVIDENCE_REFERENCE_INVALID", "Target evidence requires a case target reference.");
        InvestigationCaseRepository.TargetRow linkedTarget = cases.targets(caseId).stream()
                .filter(value -> value.targetId().equals(referenceId)).findFirst()
                .orElseThrow(() -> invalid("INVESTIGATION_EVIDENCE_REFERENCE_INVALID",
                        "The target is not linked to this case."));
        TargetContext current = target(linkedTarget.targetType(), linkedTarget.targetId());
        return evidenceType == EvidenceType.CURRENT_TARGET_SNAPSHOT
                ? current.currentState()
                : mapper.valueToTree(Map.of("targetType", linkedTarget.targetType().name(),
                        "targetId", linkedTarget.targetId(), "activeEnforcement", current.enforcement(),
                        "capturedAt", clock.instant().toString()));
    }

    private Capabilities capabilities(InvestigationCaseRepository.CaseRow row, User admin,
                                      CaseEnforcementService.Plan plan) {
        var access = authorization.accessFor(admin);
        boolean mine = admin.getId().equals(row.assignedAdminId());
        boolean other = row.assignedAdminId() != null && !mine;
        boolean finalState = row.status() == Status.CLOSED_NO_ACTION || row.status() == Status.CLOSED_ACTIONED;
        boolean canAssign = access.has(AdminPermission.REPORT_ASSIGN);
        boolean canInvestigate = access.has(AdminPermission.REPORT_INVESTIGATE);
        boolean canResolve = access.has(AdminPermission.REPORT_RESOLVE);
        boolean active = row.status() == Status.UNDER_INVESTIGATION && mine;
        boolean ready = row.status() == Status.READY_FOR_ACTION;
        boolean canCreateProposal = ready && mine && canResolve;
        boolean canCloseActioned = canCreateProposal && !plan.proposals().isEmpty()
                && plan.proposals().stream().anyMatch(value -> value.status() == ProposalStatus.EXECUTED)
                && plan.proposals().stream().allMatch(value -> value.status() == ProposalStatus.EXECUTED
                        || value.status() == ProposalStatus.CANCELLED);
        boolean canOperateProposal = plan.proposals().stream().anyMatch(value -> {
            var capability = value.availableAdminCapabilities();
            return capability.canEdit() || capability.canCancel() || capability.canDryRun()
                    || capability.canExecute() || capability.canRetry();
        });
        boolean canClaim = canAssign && INVESTIGABLE.contains(row.status()) && row.assignedAdminId() == null;
        boolean canRelease = canAssign && INVESTIGABLE.contains(row.status()) && mine;
        boolean canStart = canInvestigate && row.status() == Status.OPEN && mine;
        String reason = finalState ? "This investigation is concluded and read-only."
                : ready && !canCreateProposal && !canOperateProposal
                        ? "This case is ready for action, but you have no available plan action."
                : other ? "This case is assigned to another admin."
                : row.assignedAdminId() == null ? "Claim this case to investigate."
                : row.status() == Status.OPEN ? "Start the investigation before recording findings."
                : !canInvestigate ? "You do not have investigation permission." : null;
        return new Capabilities(true, canClaim, canRelease, canStart, canInvestigate && active,
                canInvestigate && active, canInvestigate && active, canInvestigate && active,
                canInvestigate && active, canInvestigate && active, canInvestigate && active,
                canResolve && active && row.reportCount() > 0, canResolve && active,
                canCreateProposal, canCloseActioned, ready, mine, other,
                finalState, finalState || (!canClaim && !canRelease && !canStart && !active
                        && !canCreateProposal && !canOperateProposal), reason);
    }

    private Summary summary(InvestigationCaseRepository.CaseRow row) {
        return new Summary(row.id(), row.title(), row.status(), row.severity(), row.primaryTargetType(),
                row.primaryTargetId(), row.safePrimaryTargetLabel(), row.assignedAdminId(), row.reportCount(),
                row.targetCount(), row.noteCount(), row.createdAt(), row.updatedAt(), row.version());
    }

    private InvestigationCaseRepository.CaseRow caseRow(String rawId) {
        return cases.find(id("Case ID", rawId)).orElseThrow(InvestigationCaseException::notFound);
    }

    private ReportRepository.ReportRow report(String reportId) {
        return reports.find(reportId).orElseThrow(() -> invalid("REPORT_NOT_FOUND", "Report was not found."));
    }

    private User authorized(AdminPermission permission) {
        User admin = authService.ensureUserEntity();
        authorization.requirePermission(admin, permission);
        return admin;
    }

    private void mutationConflict(String caseId, long expected, User admin) {
        InvestigationCaseRepository.CaseRow current = cases.find(caseId)
                .orElseThrow(InvestigationCaseException::notFound);
        if (current.version() != expected)
            throw conflict("INVESTIGATION_CASE_VERSION_CONFLICT", "The case changed. Refresh and try again.");
        if (!INVESTIGABLE.contains(current.status()))
            throw conflict("INVESTIGATION_CASE_READ_ONLY", "The concluded case is read-only.");
        if (current.assignedAdminId() != null && !admin.getId().equals(current.assignedAdminId()))
            throw conflict("INVESTIGATION_CASE_ASSIGNED_TO_OTHER", "The case is assigned to another admin.");
        if (current.assignedAdminId() == null)
            throw conflict("INVESTIGATION_CASE_NOT_ASSIGNED", "Claim the case before changing it.");
        if (current.status() == Status.OPEN)
            throw conflict("INVESTIGATION_NOT_STARTED", "Start the investigation before recording findings.");
        throw conflict("INVESTIGATION_CASE_NOT_MUTABLE", "The case is not valid for this action.");
    }

    private void requireMutableOwned(InvestigationCaseRepository.CaseRow value, long expected, User admin) {
        if (value.version() != expected)
            throw conflict("INVESTIGATION_CASE_VERSION_CONFLICT", "The case changed. Refresh and try again.");
        if (value.status() != Status.UNDER_INVESTIGATION)
            throw conflict(INVESTIGABLE.contains(value.status()) ? "INVESTIGATION_NOT_STARTED" : "INVESTIGATION_CASE_READ_ONLY",
                    INVESTIGABLE.contains(value.status()) ? "Start the investigation before recording findings."
                            : "The concluded case is read-only.");
        if (!admin.getId().equals(value.assignedAdminId()))
            throw conflict(value.assignedAdminId() == null ? "INVESTIGATION_CASE_NOT_ASSIGNED"
                    : "INVESTIGATION_CASE_ASSIGNED_TO_OTHER",
                    value.assignedAdminId() == null ? "Claim the case before changing it."
                            : "The case is assigned to another admin.");
    }

    private InvestigationCaseRepository.EventRow event(String caseId, String type, User actor,
                                                         String previous, String next, String reasonCode,
                                                         String reason, String correlationId,
                                                         Map<String, String> metadata) {
        return new InvestigationCaseRepository.EventRow(ulids.next(), caseId, type, clock.instant(), actor.getId(),
                safeName(actor), previous, next, reasonCode, reason, correlationId, ulids.next(), metadata);
    }

    private ReportRepository.EventRow reportEvent(String reportId, ReportRepository.ReportRow report, User actor,
                                                   String type, ReportContracts.Status previous,
                                                   ReportContracts.Status next, String reasonCode, String reason,
                                                   String correlationId, Map<String, String> metadata) {
        return new ReportRepository.EventRow(ulids.next(), reportId, type, clock.instant(), "PLATFORM_ADMIN",
                actor.getId(), safeName(actor), "HUMAN_ADMIN", previous.name(), next.name(), reasonCode, reason,
                correlationId, ulids.next(), metadata);
    }

    private static InvestigationCaseException invalid(String code, String message) {
        return InvestigationCaseException.invalid(code, message);
    }

    private static InvestigationCaseException conflict(String code, String message) {
        return InvestigationCaseException.conflict(code, message);
    }

    private static InvestigationCaseException reportVersionConflict() {
        return conflict("REPORT_VERSION_CONFLICT", "The report changed. Refresh and try again.");
    }

    private long version(Long value, String aggregate) {
        if (value == null || value < 0)
            throw invalid("INVESTIGATION_VERSION_REQUIRED", "The current " + aggregate + " version is required.");
        return value;
    }

    private String id(String name, String value) {
        try { return FixedLengthIds.requireTrimmed(name, value, 26); }
        catch (IllegalArgumentException exception) { throw invalid("INVESTIGATION_ID_INVALID", exception.getMessage()); }
    }

    private String requiredText(String value, int max, String name) {
        String normalized = optionalText(value, max, name);
        if (normalized == null) throw invalid("INVESTIGATION_TEXT_REQUIRED", name + " is required.");
        return normalized;
    }

    private String optionalText(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid("INVESTIGATION_TEXT_TOO_LONG", name + " is too long.");
        if (normalized.chars().anyMatch(Character::isISOControl))
            throw invalid("INVESTIGATION_TEXT_INVALID", name + " contains unsupported control characters.");
        return normalized;
    }

    private Status operationalStatus(Status value) {
        return value;
    }

    private ReportContracts.TargetType operationalTarget(ReportContracts.TargetType value) {
        if (value == null || !Set.of(ReportContracts.TargetType.USER, ReportContracts.TargetType.BUSINESS,
                ReportContracts.TargetType.LISTING).contains(value))
            throw invalid("INVESTIGATION_TARGET_TYPE_INVALID", "Only user, business, and listing targets are operational.");
        return value;
    }

    private String normalizedSort(String value) {
        String sort = value == null || value.isBlank() ? "updatedAt,desc" : value.trim();
        if (!Set.of("updatedAt,desc", "updatedAt,asc", "createdAt,asc", "severity,desc").contains(sort))
            throw invalid("INVESTIGATION_SORT_INVALID", "Sort is invalid.");
        return sort;
    }

    private String safeName(User user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? "Platform admin" : user.getDisplayName().trim();
    }

    private String adminPath(ReportContracts.TargetType type, String id) {
        return switch (type) {
            case USER -> "/admin/users/" + id;
            case BUSINESS -> "/admin/businesses/" + id;
            case LISTING -> "/admin/listings/moderation?query=" + id;
            default -> null;
        };
    }

    private record TargetContext(String label, JsonNode currentState,
                                 List<ReportContracts.EnforcementSummary> enforcement) { }
}
