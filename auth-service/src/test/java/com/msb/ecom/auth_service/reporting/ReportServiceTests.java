package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.reporting.ReportContracts.CreateReportRequest;
import com.msb.ecom.auth_service.reporting.ReportContracts.ListingTargetContext;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReasonCode;
import com.msb.ecom.auth_service.reporting.ReportContracts.Status;
import com.msb.ecom.auth_service.reporting.ReportContracts.TargetType;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final String REPORTER = "01JREPORTER000000000000000";
    private static final String LISTING = "01JLISTING0000000000000000";
    private static final String REPORT = "01JREPORT00000000000000000";

    @Mock ReportRepository repository;
    @Mock InvestigationCaseRepository investigationCases;
    @Mock ProductReportTargetClient productTargets;
    @Mock AuthService authService;
    @Mock AdminAuthorizationService adminAuthorization;
    @Mock EnforcementService enforcementService;
    @Mock UlidGenerator ulids;
    @Mock User reporter;
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(repository, investigationCases, productTargets, authService, adminAuthorization,
                enforcementService, ulids, new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listingSubmissionDerivesReporterAndPersistsServerSnapshotWithoutEnforcement() {
        when(authService.ensureUserEntity()).thenReturn(reporter);
        when(reporter.getId()).thenReturn(REPORTER);
        when(reporter.getDisplayName()).thenReturn("Buyer One");
        when(productTargets.listing(LISTING)).thenReturn(Optional.of(listing("Seller title", "01JSELLER00000000000000000")));
        when(repository.incrementRate(eq(REPORTER), any(), any(), any())).thenReturn(1);
        when(ulids.next()).thenReturn(REPORT, "01JEVENT00000000000000000", "01JREQUEST000000000000000");
        when(repository.reserveDuplicate(eq(REPORTER), eq(TargetType.LISTING), eq(LISTING),
                eq(ReasonCode.MISLEADING_LISTING), eq(REPORT), any(), any())).thenReturn(true);

        var result = service.submit(new CreateReportRequest(TargetType.LISTING, LISTING,
                ReasonCode.MISLEADING_LISTING, "Title does not match the item"), "corr-1");

        assertThat(result.reportId()).isEqualTo(REPORT);
        assertThat(result.status()).isEqualTo(Status.SUBMITTED);
        ArgumentCaptor<ReportRepository.ReportRow> row = ArgumentCaptor.forClass(ReportRepository.ReportRow.class);
        verify(repository).insert(row.capture());
        assertThat(row.getValue().reporterUserId()).isEqualTo(REPORTER);
        assertThat(row.getValue().targetSnapshot().get("title").asText()).isEqualTo("Seller title");
        assertThat(row.getValue().targetSnapshot().has("individualSellerUserId")).isTrue();
        verifyNoInteractions(enforcementService);
    }

    @Test
    void duplicateSubmissionReturnsStableConflictForTransactionRollback() {
        when(authService.ensureUserEntity()).thenReturn(reporter);
        when(reporter.getId()).thenReturn(REPORTER);
        when(productTargets.listing(LISTING)).thenReturn(Optional.of(listing("Title", "01JSELLER00000000000000000")));
        when(repository.incrementRate(eq(REPORTER), any(), any(), any())).thenReturn(1);
        when(ulids.next()).thenReturn(REPORT);
        when(repository.reserveDuplicate(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.submit(new CreateReportRequest(TargetType.LISTING, LISTING,
                ReasonCode.SPAM, null), "corr-2"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_ALREADY_SUBMITTED"));
        verify(repository).insert(any());
    }

    @Test
    void otherRequiresDescriptionBeforeTargetLookup() {
        when(authService.ensureUserEntity()).thenReturn(reporter);

        assertThatThrownBy(() -> service.submit(new CreateReportRequest(TargetType.LISTING, LISTING,
                ReasonCode.OTHER, "  "), "corr-3"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_DESCRIPTION_REQUIRED"));
        verifyNoInteractions(productTargets);
    }

    @Test
    void sellerCannotReportOwnListing() {
        when(authService.ensureUserEntity()).thenReturn(reporter);
        when(reporter.getId()).thenReturn(REPORTER);
        when(productTargets.listing(LISTING)).thenReturn(Optional.of(listing("Title", REPORTER)));

        assertThatThrownBy(() -> service.submit(new CreateReportRequest(TargetType.LISTING, LISTING,
                ReasonCode.SPAM, null), "corr-4"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REPORT_SELF_TARGET_NOT_ALLOWED"));
        verify(repository, never()).insert(any());
    }

    private ListingTargetContext listing(String title, String sellerId) {
        return new ListingTargetContext(LISTING, "INDIVIDUAL", sellerId, null, null, title,
                "Original description", new BigDecimal("12.50"), "USD", "01JCATEGORY00000000000000",
                null, "ACTIVE", List.of("/api/v1/public/listing-media/image"), NOW, 3, true, List.of());
    }
}
