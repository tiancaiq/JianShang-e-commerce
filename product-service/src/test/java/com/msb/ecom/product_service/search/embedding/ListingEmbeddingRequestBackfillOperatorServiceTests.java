package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorAuditRepository;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
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

class ListingEmbeddingRequestBackfillOperatorServiceTests {
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private static final String RUN_ID = "01R00000000000000000000921";
    private static final String ADMIN_ID = "01A00000000000000000000921";
    private static final String AUDIT_ID = "01T00000000000000000000921";

    private final CurrentActorProvider actorProvider = mock(CurrentActorProvider.class);
    private final AuthServiceClient authServiceClient = mock(AuthServiceClient.class);
    private final ListingEmbeddingRequestBackfillProcessor processor = mock(
            ListingEmbeddingRequestBackfillProcessor.class);
    private final ListingEmbeddingRequestBackfillRepository repository = mock(
            ListingEmbeddingRequestBackfillRepository.class);
    private final ListingSearchOperatorAuditRepository auditRepository = mock(
            ListingSearchOperatorAuditRepository.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);

    @BeforeEach
    void authenticatedAdmin() {
        when(actorProvider.currentActor()).thenReturn(
                new CurrentActor("subject", "token", null, null, false));
        when(authServiceClient.requirePlatformAdmin("token")).thenReturn(
                new AuthServiceClient.PlatformAdminAuthorization(ADMIN_ID, "PLATFORM_ADMIN"));
        when(ulids.next()).thenReturn(AUDIT_ID);
    }

    @Test
    void disabledAuthenticatesThenPerformsZeroRunOrAuditWork() {
        assertThatThrownBy(() -> service(false, false).start("corr-disabled"))
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .extracting("kind")
                .isEqualTo(ListingEmbeddingRequestBackfillException.Kind.DISABLED);

        verify(authServiceClient).requirePlatformAdmin("token");
        verifyNoInteractions(processor, repository, auditRepository);
    }

    @Test
    void deniedOrUnavailableAuthStopsBeforeGateAndResourceLookup() {
        when(authServiceClient.requirePlatformAdmin("token"))
                .thenThrow(new ListingAuthorizationException("denied"));
        assertThatThrownBy(() -> service(true, true).start("corr-denied"))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(processor, repository, auditRepository);

        reset(authServiceClient);
        when(authServiceClient.requirePlatformAdmin("token"))
                .thenThrow(new IllegalStateException("auth body"));
        assertThatThrownBy(() -> service(true, true).status(RUN_ID))
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .hasMessageNotContaining("auth body");
        verifyNoInteractions(processor, repository, auditRepository);
    }

    @Test
    void startAndCompletedResumeProduceBoundedAuditedOutcomes() {
        ListingEmbeddingRequestBackfillRun pending = run("PENDING");
        ListingEmbeddingRequestBackfillRun completed = run("COMPLETED");
        when(repository.findActive()).thenReturn(Optional.empty());
        when(processor.start()).thenReturn(pending);
        when(processor.status(RUN_ID)).thenReturn(completed);
        when(processor.resume(RUN_ID)).thenReturn(completed);

        ListingEmbeddingRequestBackfillOperatorService service = service(true, true);
        assertThat(service.start("corr-start").state()).isEqualTo("PENDING");
        assertThat(service.resume(RUN_ID, "corr-resume").commandOutcome())
                .isEqualTo("REPLAYED");

        verify(auditRepository).appendEmbeddingBackfill(
                eq(AUDIT_ID), eq(ADMIN_ID), eq("EMBEDDING_START"), eq(RUN_ID),
                eq(null), eq("PENDING"), eq("SUCCEEDED"), eq(null),
                eq("corr-start"), eq(NOW));
        verify(auditRepository).appendEmbeddingBackfill(
                eq(AUDIT_ID), eq(ADMIN_ID), eq("EMBEDDING_RESUME"), eq(RUN_ID),
                eq("COMPLETED"), eq("COMPLETED"), eq("REPLAYED"), eq(null),
                eq("corr-resume"), eq(NOW));
    }

    @Test
    void statusUsesIndependentReadGateAndValidatesRunIdAfterAuth() {
        assertThatThrownBy(() -> service(true, false).status(RUN_ID))
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class);
        verify(processor, never()).status(any());

        assertThatThrownBy(() -> service(true, true).status("lowercase-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(authServiceClient, org.mockito.Mockito.atLeast(2))
                .requirePlatformAdmin("token");
    }

    private ListingEmbeddingRequestBackfillOperatorService service(
            boolean commandsEnabled,
            boolean statusEnabled) {
        return new ListingEmbeddingRequestBackfillOperatorService(
                new ListingEmbeddingRequestBackfillProperties(
                        commandsEnabled, statusEnabled, 50, 100_000, Duration.ofMinutes(2)),
                actorProvider,
                authServiceClient,
                processor,
                repository,
                auditRepository,
                ulids,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingEmbeddingRequestBackfillRun run(String state) {
        return new ListingEmbeddingRequestBackfillRun(
                RUN_ID,
                "01D00000000000000000000929",
                "COMPLETED".equals(state) ? "01D00000000000000000000929" : null,
                state,
                "COMPLETED".equals(state) ? 1 : 0,
                "COMPLETED".equals(state) ? 1 : 0,
                "COMPLETED".equals(state) ? 1 : 0,
                0,
                0,
                0,
                null,
                null,
                null,
                NOW,
                NOW,
                "COMPLETED".equals(state) ? NOW : null);
    }
}
