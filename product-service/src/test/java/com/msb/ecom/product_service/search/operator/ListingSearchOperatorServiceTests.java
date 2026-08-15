package com.msb.ecom.product_service.search.operator;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.ListingSearchPromotionService;
import com.msb.ecom.product_service.search.ListingSearchPromotionEligibility;
import com.msb.ecom.product_service.search.ListingSearchRebuildRun;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingSearchOperatorServiceTests {
    private static final String RUN_ID = "01R00000000000000000000801";
    private static final String ADMIN_ID = "01A00000000000000000000801";
    private static final String AUDIT_ID = "01T00000000000000000000801";
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    private final CurrentActorProvider currentActorProvider = mock(CurrentActorProvider.class);
    private final AuthServiceClient authServiceClient = mock(AuthServiceClient.class);
    private final ListingSearchPromotionService promotionService =
            mock(ListingSearchPromotionService.class);
    private final ListingSearchOperatorAuditRepository auditRepository =
            mock(ListingSearchOperatorAuditRepository.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);

    @BeforeEach
    void authenticatedAdmin() {
        when(currentActorProvider.currentActor()).thenReturn(
                new CurrentActor("subject", "token", null, null, false));
        when(authServiceClient.requirePlatformAdmin("token")).thenReturn(
                new AuthServiceClient.PlatformAdminAuthorization(ADMIN_ID, "PLATFORM_ADMIN"));
        when(ulids.next()).thenReturn(AUDIT_ID);
        when(promotionService.commandEligibility(any()))
                .thenAnswer(invocation -> {
                    ListingSearchRebuildRun run = invocation.getArgument(0);
                    if ("PROMOTED".equals(run.state())) {
                        return ListingSearchPromotionEligibility.NONE;
                    }
                    if ("PROMOTION_FENCED".equals(run.state())
                            || "ROLLBACK_REQUIRED".equals(run.state())) {
                        return new ListingSearchPromotionEligibility(
                                false, false, true);
                    }
                    return new ListingSearchPromotionEligibility(
                            true, true, false);
                });
    }

    @Test
    void disabledCommandAuthenticatesThenStopsBeforeRunAuditOrOpenSearchService() {
        ListingSearchOperatorService service = service(false, false);

        assertThatThrownBy(() -> service.prepare("corr-disabled"))
                .isInstanceOf(ListingSearchOperatorFeatureDisabledException.class);

        verify(authServiceClient).requirePlatformAdmin("token");
        verifyNoInteractions(promotionService, auditRepository);
    }

    @Test
    void deniedOrUnavailableAuthStopsBeforeFeatureAndRunLookup() {
        when(authServiceClient.requirePlatformAdmin("token"))
                .thenThrow(new ListingAuthorizationException("denied"));

        assertThatThrownBy(() -> service(true, true).prepare("corr-denied"))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(promotionService, auditRepository);

        reset(authServiceClient);
        when(authServiceClient.requirePlatformAdmin("token"))
                .thenThrow(new IllegalStateException("auth transport"));
        assertThatThrownBy(() -> service(true, true).prepare("corr-auth-down"))
                .isInstanceOf(ListingSearchOperatorUnavailableException.class);
        verifyNoInteractions(promotionService, auditRepository);
    }

    @Test
    void activePrepareIsStableConflictAndAuditedWithoutAllocatingCandidate() {
        ListingSearchRebuildRun active = run("CATCHING_UP", 2, NOW);
        when(promotionService.findActiveRun()).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service(true, true).prepare("corr-active"))
                .isInstanceOf(ListingSearchOperatorConflictException.class)
                .extracting("code")
                .isEqualTo("VECTOR_REBUILD_ACTIVE");

        verify(promotionService, never()).prepareInactiveCandidate();
        verify(auditRepository).append(
                AUDIT_ID, ADMIN_ID, "PREPARE", RUN_ID,
                "CATCHING_UP", "CATCHING_UP", "FAILED",
                "VECTOR_REBUILD_ACTIVE", "corr-active", NOW);
    }

    @Test
    void prepareReturnsSafeBoundedViewAndImmutableAudit() {
        ListingSearchRebuildRun prepared = run("CATCHING_UP", 3, NOW.plusSeconds(1));
        when(promotionService.findActiveRun()).thenReturn(Optional.empty());
        when(promotionService.prepareInactiveCandidate()).thenReturn(prepared);

        ListingSearchOperatorResponse response = service(true, true).prepare("corr-prepare");

        assertThat(response.runId()).isEqualTo(RUN_ID);
        assertThat(response.state()).isEqualTo("CATCHING_UP");
        assertThat(response.candidateRole()).isEqualTo("INACTIVE_V2_CANDIDATE");
        assertThat(response.canCatchUp()).isTrue();
        assertThat(response.canPromote()).isTrue();
        assertThat(response.canRecover()).isFalse();
        assertThat(response.toString()).doesNotContain("marketplace-listings-v2-g");
        verify(auditRepository).append(
                AUDIT_ID, ADMIN_ID, "PREPARE", RUN_ID,
                null, "CATCHING_UP", "SUCCEEDED", null,
                "corr-prepare", NOW);
    }

    @Test
    void internalCatalogValidationFailureIsAuditedAndMappedUnavailable() {
        when(promotionService.findActiveRun())
                .thenReturn(Optional.empty(), Optional.empty());
        when(promotionService.prepareInactiveCandidate())
                .thenThrow(new IllegalArgumentException("sensitive catalog detail"));

        assertThatThrownBy(() -> service(true, true).prepare("corr-invalid-catalog"))
                .isInstanceOf(ListingSearchOperatorUnavailableException.class)
                .hasMessageNotContaining("sensitive catalog detail");

        verify(auditRepository).append(
                AUDIT_ID, ADMIN_ID, "PREPARE", null,
                null, null, "FAILED", "VECTOR_REBUILD_UNAVAILABLE",
                "corr-invalid-catalog", NOW);
    }

    @Test
    void statusHasIndependentReadGateAndValidatesCanonicalRunIdAfterAuth() {
        ListingSearchOperatorService disabled = service(true, false);
        assertThatThrownBy(() -> disabled.status(RUN_ID))
                .isInstanceOf(ListingSearchOperatorFeatureDisabledException.class);
        verify(promotionService, never()).findRun(any());

        assertThatThrownBy(() -> service(true, true).status("lowercase-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(authServiceClient, org.mockito.Mockito.atLeast(2))
                .requirePlatformAdmin("token");
    }

    @Test
    void statusWithCommandsDisabledReturnsNoEligibilityWithoutCommandInspection() {
        ListingSearchRebuildRun run = run("CATCHING_UP", 0, NOW);
        when(promotionService.findRun(RUN_ID)).thenReturn(Optional.of(run));

        ListingSearchOperatorResponse response =
                service(false, true).status(RUN_ID);

        assertThat(response.canCatchUp()).isFalse();
        assertThat(response.canPromote()).isFalse();
        assertThat(response.canRecover()).isFalse();
        verify(promotionService, never()).commandEligibility(any());
    }

    @Test
    void statusMapsEligibilityDependencyFailureToSafeUnavailableError() {
        ListingSearchRebuildRun run = run("CATCHING_UP", 0, NOW);
        when(promotionService.findRun(RUN_ID)).thenReturn(Optional.of(run));
        when(promotionService.commandEligibility(run))
                .thenThrow(new IllegalStateException("sensitive alias response"));

        assertThatThrownBy(() -> service(true, true).status(RUN_ID))
                .isInstanceOf(ListingSearchOperatorUnavailableException.class)
                .hasMessageNotContaining("sensitive alias response");
    }

    @Test
    void promotedCommandReplayDoesNotTouchAliasesAndIsAuditedAsReplay() {
        ListingSearchRebuildRun promoted = run("PROMOTED", 4, NOW.plusSeconds(2));
        when(promotionService.findRun(RUN_ID)).thenReturn(Optional.of(promoted));

        ListingSearchOperatorResponse response =
                service(true, true).promote(RUN_ID, "corr-replay");

        assertThat(response.commandOutcome()).isEqualTo("REPLAYED");
        assertThat(response.candidateRole()).isEqualTo("ACTIVE_V2");
        verify(promotionService, never()).promote(any());
        verify(auditRepository).append(
                AUDIT_ID, ADMIN_ID, "PROMOTE", RUN_ID,
                "PROMOTED", "PROMOTED", "REPLAYED", null,
                "corr-replay", NOW);
    }

    @Test
    void catchUpAndRecoverMapOnlyExistingDurableMethods() {
        ListingSearchRebuildRun catchUpBefore = run("CATCHING_UP", 1, NOW);
        ListingSearchRebuildRun recoveryBefore = run("PROMOTION_FENCED", 1, NOW);
        ListingSearchRebuildRun caughtUp = run("CATCHING_UP", 2, NOW.plusSeconds(1));
        ListingSearchRebuildRun recovered = run("PROMOTED", 2, NOW.plusSeconds(2));
        when(promotionService.findRun(RUN_ID))
                .thenReturn(
                        Optional.of(catchUpBefore),
                        Optional.of(caughtUp),
                        Optional.of(recoveryBefore));
        when(promotionService.recover(RUN_ID)).thenReturn(recovered);

        ListingSearchOperatorService service = service(true, true);
        assertThat(service.catchUp(RUN_ID, "corr-catch").catchUpWorkCount()).isEqualTo(2);
        assertThat(service.recover(RUN_ID, "corr-recover").state()).isEqualTo("PROMOTED");

        verify(promotionService).catchUp(RUN_ID);
        verify(promotionService).recover(RUN_ID);
        verify(auditRepository).append(
                eq(AUDIT_ID), eq(ADMIN_ID), eq("CATCH_UP"), eq(RUN_ID),
                eq("CATCHING_UP"), eq("CATCHING_UP"), eq("SUCCEEDED"),
                eq(null), eq("corr-catch"), eq(NOW));
        verify(auditRepository).append(
                eq(AUDIT_ID), eq(ADMIN_ID), eq("RECOVER"), eq(RUN_ID),
                eq("PROMOTION_FENCED"), eq("PROMOTED"), eq("SUCCEEDED"),
                eq(null), eq("corr-recover"), eq(NOW));
    }

    @Test
    void commandPreflightUsesServerEligibilityAndRejectsStaleOperatorState() {
        ListingSearchRebuildRun run = run("CATCHING_UP", 0, NOW);
        when(promotionService.findRun(RUN_ID)).thenReturn(Optional.of(run));
        when(promotionService.commandEligibility(run))
                .thenReturn(new ListingSearchPromotionEligibility(
                        true, false, false));

        assertThatThrownBy(() -> service(true, true)
                .promote(RUN_ID, "corr-not-ready"))
                .isInstanceOf(ListingSearchOperatorConflictException.class)
                .extracting("code")
                .isEqualTo("VECTOR_REBUILD_STATE_CONFLICT");

        verify(promotionService, never()).promote(any());
    }

    private ListingSearchOperatorService service(boolean commands, boolean status) {
        return new ListingSearchOperatorService(
                new ListingSearchOperatorProperties(commands, status),
                currentActorProvider,
                authServiceClient,
                promotionService,
                auditRepository,
                ulids,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingSearchRebuildRun run(String state, int catchUpCount, Instant updatedAt) {
        return new ListingSearchRebuildRun(
                RUN_ID,
                "marketplace-listings-v2-g20260724000000001",
                "marketplace-listings-v1",
                "marketplace-listings-v1",
                1,
                1L,
                state,
                5,
                3,
                catchUpCount,
                2,
                null,
                NOW,
                updatedAt,
                "PROMOTED".equals(state) ? updatedAt : null);
    }
}
