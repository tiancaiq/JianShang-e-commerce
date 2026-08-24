package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseEnforcementServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final String CASE = "01ARZ3NDEKTSV4RRFFQ69G7AAA";
    private static final String PROPOSAL = "01ARZ3NDEKTSV4RRFFQ69G7AAB";
    private static final String USER = "01ARZ3NDEKTSV4RRFFQ69G7AAC";
    private static final String BUSINESS = "01ARZ3NDEKTSV4RRFFQ69G7AAF";
    private static final String LISTING = "01ARZ3NDEKTSV4RRFFQ69G7AAG";
    private static final String ADMIN = "01ARZ3NDEKTSV4RRFFQ69G7AAD";
    private static final String OTHER_ADMIN = "01ARZ3NDEKTSV4RRFFQ69G7AAH";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G7AAE";

    @Mock CaseEnforcementRepository proposals;
    @Mock InvestigationCaseRepository cases;
    @Mock AdminUserService users;
    @Mock AdminBusinessService businesses;
    @Mock CaseListingEnforcementClient listings;
    @Mock AuthService authService;
    @Mock AdminAuthorizationService authorization;
    @Mock UlidGenerator ulids;
    @Mock TransactionTemplate transactions;
    @Mock User admin;
    private CaseEnforcementService service;

    @BeforeEach
    void setUp() {
        service = new CaseEnforcementService(proposals, cases, users, businesses, listings, authService,
                authorization, ulids, new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC),
                transactions);
        lenient().when(authService.ensureUserEntity()).thenReturn(admin);
        lenient().when(admin.getId()).thenReturn(ADMIN);
        lenient().when(admin.getDisplayName()).thenReturn("Admin One");
        lenient().when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.report.resolve", "admin.user.suspend")));
        lenient().when(ulids.next()).thenReturn(PROPOSAL);
    }

    @Test
    void readyCaseCreatesDraftForLinkedTargetWithoutEnforcement() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(4)));
        when(cases.hasTarget(CASE, ReportContracts.TargetType.USER, USER)).thenReturn(true);
        when(cases.touchReadyOwned(CASE, 4, ADMIN, NOW)).thenReturn(true);

        service.create(CASE, new CreateProposalRequest(ReportContracts.TargetType.USER, USER,
                ActionType.SUSPEND, Set.of(Scope.USER_BUYING), "POLICY_VIOLATION", "Repeated fraud", null,
                null, 4L, 2L), "corr");

        verify(proposals).insert(argThat(value -> value.status() == ProposalStatus.DRAFT
                && value.caseId().equals(CASE) && value.targetId().equals(USER)));
        verifyNoInteractions(users, businesses, listings);
    }

    @Test
    void unlinkedTargetIsRejectedBeforeProposalPersistence() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(4)));
        when(cases.hasTarget(CASE, ReportContracts.TargetType.USER, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.create(CASE, new CreateProposalRequest(
                ReportContracts.TargetType.USER, USER, ActionType.RESTRICT, Set.of(Scope.USER_SELLING),
                "POLICY_VIOLATION", "Unlinked target", null, null, 4L, 2L), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("CASE_TARGET_NOT_LINKED"));
        verify(proposals, never()).insert(any());
    }

    @Test
    void validatedProposalExecutesOnceAndCreatesDedicatedCaseLink() {
        CaseEnforcementRepository.ProposalRow before = proposal(ProposalStatus.VALIDATED, 3, null);
        CaseEnforcementRepository.ProposalRow pinned = proposal(ProposalStatus.VALIDATED, 4, "case-exec-" + PROPOSAL);
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)));
        when(proposals.find(CASE, PROPOSAL)).thenReturn(Optional.of(before), Optional.of(pinned));
        when(cases.hasTarget(CASE, ReportContracts.TargetType.USER, USER)).thenReturn(true);
        when(cases.touchReadyOwned(CASE, 8, ADMIN, NOW)).thenReturn(true);
        when(proposals.pinExecution(CASE, PROPOSAL, 3, "case-exec-" + PROPOSAL, NOW)).thenReturn(true);
        when(proposals.executed(CASE, PROPOSAL, 4, ACTION, NOW)).thenReturn(true);
        when(users.createCaseConfirmed(eq(USER), any(), eq(CASE))).thenReturn(new com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result(
                ACTION, com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType.USER, USER,
                ActionType.SUSPEND, Set.of(Scope.USER_BUYING),
                com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState.ACTIVE,
                NOW, null, 0, NOW, null, "POLICY_VIOLATION", "Repeated fraud", List.of(), "corr", false));
        executeTransactionsImmediately();

        service.execute(CASE, PROPOSAL, new ExecuteProposalRequest(8L, 3L,
                "case-exec-" + PROPOSAL), "corr");

        verify(users, times(1)).createCaseConfirmed(eq(USER), any(), eq(CASE));
        verify(proposals).link(argThat(link -> link.enforcementActionId().equals(ACTION)
                && link.proposalId().equals(PROPOSAL)));
    }

    @Test
    void businessAndListingDryRunsUseOnlyTheirExistingTargetAdapters() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)), Optional.of(caseRow(9)));
        CaseEnforcementRepository.ProposalRow business = proposal(ReportContracts.TargetType.BUSINESS,
                BUSINESS, ActionType.SUSPEND, Set.of(Scope.BUSINESS_NEW_SALES), ProposalStatus.DRAFT, 2, null);
        CaseEnforcementRepository.ProposalRow listing = proposal(ReportContracts.TargetType.LISTING,
                LISTING, ActionType.SUSPEND, Set.of(Scope.LISTING_PUBLIC_VISIBILITY), ProposalStatus.DRAFT, 4, null);
        when(proposals.find(CASE, PROPOSAL)).thenReturn(Optional.of(business), Optional.of(listing));
        when(cases.hasTarget(eq(CASE), any(), anyString())).thenReturn(true);
        when(proposals.validated(eq(CASE), eq(PROPOSAL), anyLong(), eq(2L), any(), eq(NOW))).thenReturn(true);
        when(listings.dryRun(any())).thenReturn(new CaseListingEnforcementClient.DispatchResult(
                null, new ObjectMapper().createObjectNode()));
        when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.report.resolve", "admin.business.suspend",
                "admin.listing.suspend")));

        service.dryRun(CASE, PROPOSAL, new ProposalVersionRequest(8L, 2L), "corr-business");
        service.dryRun(CASE, PROPOSAL, new ProposalVersionRequest(9L, 4L), "corr-listing");

        verify(businesses).createCasePreview(eq(BUSINESS), any(), eq(CASE));
        verify(listings).dryRun(argThat(command -> command.listingId().equals(LISTING)
                && command.caseId().equals(CASE)));
        verify(cases, never()).touchReadyOwned(anyString(), anyLong(), anyString(), any());
        verify(cases, never()).insertEvent(any());
        verifyNoInteractions(users);
    }

    @Test
    void targetPermissionIsRequiredBeforeDryRunOrExecutionDispatch() {
        CaseEnforcementRepository.ProposalRow ban = proposal(ReportContracts.TargetType.USER, USER,
                ActionType.BAN, Set.of(Scope.USER_BUYING, Scope.USER_SELLING), ProposalStatus.VALIDATED, 3, null);
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)));
        when(proposals.find(CASE, PROPOSAL)).thenReturn(Optional.of(ban));
        when(cases.hasTarget(CASE, ReportContracts.TargetType.USER, USER)).thenReturn(true);

        assertThatThrownBy(() -> service.dryRun(CASE, PROPOSAL,
                new ProposalVersionRequest(8L, 3L), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("CASE_ENFORCEMENT_PERMISSION_DENIED"));
        verifyNoInteractions(users, businesses, listings);
    }

    @Test
    void failedListingExecutionIsRecordedWithoutFakeRollbackOrLink() {
        CaseEnforcementRepository.ProposalRow before = proposal(ReportContracts.TargetType.LISTING, LISTING,
                ActionType.SUSPEND, Set.of(Scope.LISTING_PUBLIC_VISIBILITY), ProposalStatus.VALIDATED, 3, null);
        CaseEnforcementRepository.ProposalRow pinned = proposal(ReportContracts.TargetType.LISTING, LISTING,
                ActionType.SUSPEND, Set.of(Scope.LISTING_PUBLIC_VISIBILITY), ProposalStatus.VALIDATED, 4,
                "case-exec-" + PROPOSAL);
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)));
        when(proposals.find(CASE, PROPOSAL)).thenReturn(Optional.of(before), Optional.of(pinned));
        when(cases.hasTarget(CASE, ReportContracts.TargetType.LISTING, LISTING)).thenReturn(true);
        when(cases.touchReadyOwned(CASE, 8, ADMIN, NOW)).thenReturn(true);
        when(proposals.pinExecution(CASE, PROPOSAL, 3, "case-exec-" + PROPOSAL, NOW)).thenReturn(true);
        when(listings.execute(any())).thenThrow(new CaseEnforcementDispatchException(
                "CASE_TARGET_VERSION_CONFLICT", 409, "Target changed."));
        when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.report.resolve", "admin.listing.suspend")));
        executeTransactionsImmediately();

        assertThatThrownBy(() -> service.execute(CASE, PROPOSAL,
                new ExecuteProposalRequest(8L, 3L, "case-exec-" + PROPOSAL), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("CASE_TARGET_VERSION_CONFLICT"));

        verify(proposals).failed(CASE, PROPOSAL, 4, "CASE_TARGET_VERSION_CONFLICT", "Target changed.", NOW);
        verify(proposals, never()).link(any());
    }

    @Test
    void actionedClosureRequiresExecutedWorkAndNoUnresolvedProposal() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)), Optional.of(caseRow(8)));
        when(proposals.executedCount(CASE)).thenReturn(0L, 1L);
        when(proposals.unresolvedCount(CASE)).thenReturn(1L);

        assertThatThrownBy(() -> service.closeActioned(CASE,
                new CloseActionedRequest(8L, "Plan completed."), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("CASE_ENFORCEMENT_REQUIRED"));
        assertThatThrownBy(() -> service.closeActioned(CASE,
                new CloseActionedRequest(8L, "Plan completed."), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("CASE_HAS_UNRESOLVED_PROPOSALS"));
        verify(cases, never()).closeActioned(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void resolvedPlanClosesWithoutCreatingAnotherEnforcement() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8)));
        when(proposals.executedCount(CASE)).thenReturn(1L);
        when(proposals.unresolvedCount(CASE)).thenReturn(0L);
        when(cases.closeActioned(CASE, 8, ADMIN, "Plan completed.", NOW)).thenReturn(true);

        service.closeActioned(CASE, new CloseActionedRequest(8L, "Plan completed."), "corr");

        verify(cases).closeActioned(CASE, 8, ADMIN, "Plan completed.", NOW);
        verifyNoInteractions(users, businesses, listings);
    }

    @Test
    void onlyAssignedInvestigatorMayDryRunOrExecuteProposal() {
        when(cases.find(CASE)).thenReturn(Optional.of(caseRow(8, OTHER_ADMIN)));

        assertThatThrownBy(() -> service.dryRun(CASE, PROPOSAL,
                new ProposalVersionRequest(8L, 3L), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("INVESTIGATION_CASE_ASSIGNED_TO_OTHER"));

        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        assertThatThrownBy(() -> service.execute(CASE, PROPOSAL,
                new ExecuteProposalRequest(8L, 3L, "case-exec-" + PROPOSAL), "corr"))
                .isInstanceOfSatisfying(InvestigationCaseException.class,
                        error -> assertThat(error.code()).isEqualTo("INVESTIGATION_CASE_ASSIGNED_TO_OTHER"));
        verify(proposals, never()).find(anyString(), anyString());
        verifyNoInteractions(users, businesses, listings);
    }

    @Test
    void proposalCapabilitiesRequireCaseOwnershipAndReportResolutionPermission() {
        when(cases.targets(CASE)).thenReturn(List.of());
        when(proposals.list(CASE)).thenReturn(List.of(proposal(ProposalStatus.VALIDATED, 3, null)));
        when(proposals.links(CASE)).thenReturn(List.of());
        when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("USER_RESTRICTOR"), List.of("admin.user.suspend")));

        ProposalCapabilities withoutResolve = service.plan(caseRow(8), admin).proposals().getFirst()
                .availableAdminCapabilities();
        assertThat(withoutResolve.canEdit()).isFalse();
        assertThat(withoutResolve.canDryRun()).isFalse();
        assertThat(withoutResolve.canExecute()).isFalse();
        assertThat(withoutResolve.hasRequiredTargetPermission()).isTrue();

        when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.report.resolve", "admin.user.suspend")));
        ProposalCapabilities assignedToOther = service.plan(caseRow(8, OTHER_ADMIN), admin).proposals().getFirst()
                .availableAdminCapabilities();
        assertThat(assignedToOther.canEdit()).isFalse();
        assertThat(assignedToOther.canDryRun()).isFalse();
        assertThat(assignedToOther.canExecute()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionsImmediately() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private InvestigationCaseRepository.CaseRow caseRow(long version) {
        return caseRow(version, ADMIN);
    }

    private InvestigationCaseRepository.CaseRow caseRow(long version, String assignedAdminId) {
        return new InvestigationCaseRepository.CaseRow(CASE, "Case", Status.READY_FOR_ACTION,
                ReportContracts.Severity.HIGH, ReportContracts.TargetType.USER, USER, "Marketplace user",
                assignedAdminId, ADMIN, "ACTION_REVIEW_REQUIRED", "Ready", NOW, null, "corr", version, NOW, NOW,
                1, 1, 0);
    }

    private CaseEnforcementRepository.ProposalRow proposal(ProposalStatus status, long version, String key) {
        return proposal(ReportContracts.TargetType.USER, USER, ActionType.SUSPEND, Set.of(Scope.USER_BUYING),
                status, version, key);
    }

    private CaseEnforcementRepository.ProposalRow proposal(ReportContracts.TargetType targetType, String targetId,
            ActionType actionType, Set<Scope> scopes, ProposalStatus status, long version, String key) {
        return new CaseEnforcementRepository.ProposalRow(PROPOSAL, CASE, targetType, targetId,
                actionType, scopes, "POLICY_VIOLATION", "Repeated fraud", NOW,
                null, 2, status, ADMIN, NOW, NOW, version, NOW, 2L, new ObjectMapper().valueToTree(Map.of()),
                null, key, null, null, "corr");
    }
}
