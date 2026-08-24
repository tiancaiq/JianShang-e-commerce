package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvestigationCaseServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final String CASE = "01ARZ3NDEKTSV4RRFFQ69G7AAA";
    private static final String REPORT = "01ARZ3NDEKTSV4RRFFQ69G7AAB";
    private static final String LISTING = "01ARZ3NDEKTSV4RRFFQ69G7AAC";
    private static final String ADMIN = "01ARZ3NDEKTSV4RRFFQ69G7AAD";
    private static final String OTHER_ADMIN = "01ARZ3NDEKTSV4RRFFQ69G7AAE";

    @Mock InvestigationCaseRepository cases;
    @Mock ReportRepository reports;
    @Mock ProductReportTargetClient productTargets;
    @Mock AuthService authService;
    @Mock AdminAuthorizationService authorization;
    @Mock EnforcementService enforcementService;
    @Mock CaseEnforcementService caseEnforcement;
    @Mock UlidGenerator ulids;
    @Mock User admin;
    private InvestigationCaseService service;

    @BeforeEach
    void setUp() {
        service = new InvestigationCaseService(cases, reports, productTargets, authService, authorization,
                enforcementService, caseEnforcement, ulids, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(authService.ensureUserEntity()).thenReturn(admin);
        lenient().when(admin.getId()).thenReturn(ADMIN);
        lenient().when(admin.getDisplayName()).thenReturn("Admin One");
        lenient().when(ulids.next()).thenReturn(CASE);
        lenient().when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("SUPER_ADMIN"), List.of("admin.report.read", "admin.report.assign",
                "admin.report.investigate", "admin.report.resolve")));
        lenient().when(productTargets.listing(LISTING)).thenReturn(Optional.of(listing()));
        lenient().when(caseEnforcement.plan(any(), any())).thenReturn(new CaseEnforcementService.Plan(List.of(), List.of()));
    }

    @Test
    void readyReportCreatesAtomicCaseWithDerivedTargetAndReportSeverityWithoutEnforcement() {
        when(reports.find(REPORT)).thenReturn(Optional.of(report(ReportContracts.Status.READY_FOR_INVESTIGATION, 3)));
        when(cases.markReportLinked(REPORT, 3, NOW)).thenReturn(true);
        stubDetail(caseRow(Status.OPEN, null, 0));

        Detail result = service.createFromReport(REPORT,
                new CreateRequest("Counterfeit reports — Listing", null, 3L), "corr");

        assertThat(result.caseId()).isEqualTo(CASE);
        assertThat(result.severity()).isEqualTo(ReportContracts.Severity.HIGH);
        verify(cases).insertTarget(CASE, ReportContracts.TargetType.LISTING, LISTING,
                "Walnut radio", RelationshipType.PRIMARY, ADMIN, NOW);
        verify(cases).insertReportLink(CASE, REPORT, ADMIN, NOW);
        verify(cases).markReportLinked(REPORT, 3, NOW);
        verifyNoInteractions(enforcementService);
    }

    @Test
    void invalidOrStaleReportCannotPartiallyCreateCase() {
        when(reports.find(REPORT)).thenReturn(Optional.of(report(ReportContracts.Status.DISMISSED, 3)));
        assertThatThrownBy(() -> service.createFromReport(REPORT,
                new CreateRequest("Case title", null, 3L), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_NOT_READY_FOR_INVESTIGATION"));
        verify(cases, never()).insertCase(any());

        reset(cases);
        when(reports.find(REPORT)).thenReturn(Optional.of(report(ReportContracts.Status.READY_FOR_INVESTIGATION, 4)));
        assertThatThrownBy(() -> service.createFromReport(REPORT,
                new CreateRequest("Case title", null, 3L), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_VERSION_CONFLICT"));
        verify(cases, never()).insertCase(any());
    }

    @Test
    void otherAdminCannotAddPrivateNoteAndNoNoteIsWritten() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(Status.UNDER_INVESTIGATION, OTHER_ADMIN, 5)));

        assertThatThrownBy(() -> service.addNote(CASE,
                new AddNoteRequest(5L, "Internal finding", "retry-1"), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("INVESTIGATION_CASE_ASSIGNED_TO_OTHER"));

        verify(cases, never()).insertNote(any());
    }

    @Test
    void unsafeEvidenceReferenceShapeIsRejectedBeforeMutation() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(Status.UNDER_INVESTIGATION, ADMIN, 5)));

        assertThatThrownBy(() -> service.addEvidence(CASE,
                new AddEvidenceRequest(5L, EvidenceType.REPORT_SNAPSHOT, ReferenceType.CASE_TARGET,
                        LISTING, "External-looking reference"), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("INVESTIGATION_EVIDENCE_REFERENCE_INVALID"));

        verify(cases, never()).touchInvestigableOwned(anyString(), anyLong(), anyString(), any());
        verify(cases, never()).insertEvidence(any());
    }

    @Test
    void readyForActionChangesOnlyCaseStateAndLeavesEnforcementUntouched() {
        InvestigationCaseRepository.CaseRow before = caseRow(Status.UNDER_INVESTIGATION, ADMIN, 5);
        when(cases.find(CASE)).thenReturn(Optional.of(before), Optional.of(caseRow(Status.READY_FOR_ACTION, ADMIN, 6)));
        when(cases.conclude(CASE, 5, ADMIN, Status.READY_FOR_ACTION,
                "ACTION_REVIEW_REQUIRED", "Human review completed", NOW)).thenReturn(true);
        stubCollections();

        Detail result = service.readyForAction(CASE, new ReasonRequest(5L, "Human review completed"), "corr");

        assertThat(result.status()).isEqualTo(Status.READY_FOR_ACTION);
        verify(cases).conclude(CASE, 5, ADMIN, Status.READY_FOR_ACTION,
                "ACTION_REVIEW_REQUIRED", "Human review completed", NOW);
        verifyNoInteractions(enforcementService);
    }

    private void stubDetail(InvestigationCaseRepository.CaseRow row) {
        when(cases.find(CASE)).thenReturn(Optional.of(row));
        stubCollections();
    }

    private void stubCollections() {
        lenient().when(cases.targets(CASE)).thenReturn(List.of(new InvestigationCaseRepository.TargetRow(
                ReportContracts.TargetType.LISTING, LISTING, "Walnut radio", RelationshipType.PRIMARY, NOW)));
        lenient().when(cases.reports(CASE)).thenReturn(List.of(new InvestigationCaseRepository.ReportLinkRow(
                REPORT, ReportContracts.ReasonCode.COUNTERFEIT, ReportContracts.Severity.HIGH,
                ReportContracts.Status.LINKED_TO_CASE, "Walnut radio", "Evidence",
                new ObjectMapper().valueToTree(Map.of("title", "Walnut radio")), NOW, 4)));
        lenient().when(cases.notes(CASE)).thenReturn(List.of());
        lenient().when(cases.evidence(CASE)).thenReturn(List.of());
        lenient().when(cases.events(CASE)).thenReturn(List.of());
    }

    private InvestigationCaseRepository.CaseRow caseRow(Status status, String assigned, long version) {
        return new InvestigationCaseRepository.CaseRow(CASE, "Counterfeit reports — Listing", status,
                ReportContracts.Severity.HIGH, ReportContracts.TargetType.LISTING, LISTING, "Walnut radio",
                assigned, ADMIN, null, null, status == Status.READY_FOR_ACTION ? NOW : null, null,
                "corr", version, NOW, NOW, 1, 1, 0);
    }

    private ReportRepository.ReportRow report(ReportContracts.Status status, long version) {
        return new ReportRepository.ReportRow(REPORT, "01ARZ3NDEKTSV4RRFFQ69G7AAF",
                ReportContracts.TargetType.LISTING, LISTING, "Walnut radio",
                ReportContracts.ReasonCode.COUNTERFEIT, "Evidence", ReportContracts.Severity.HIGH,
                status, null, new ObjectMapper().valueToTree(Map.of("title", "Walnut radio")), Map.of(),
                version, NOW, ADMIN, "REQUIRES_INVESTIGATION", "Needs investigation", "corr", NOW, NOW);
    }

    private ReportContracts.ListingTargetContext listing() {
        return new ReportContracts.ListingTargetContext(LISTING, "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G7AAG", null, null, "Walnut radio", "Description",
                new BigDecimal("12.00"), "USD", "01ARZ3NDEKTSV4RRFFQ69G7AAH", null,
                "ACTIVE", List.of(), NOW, 2, true, List.of());
    }
}
