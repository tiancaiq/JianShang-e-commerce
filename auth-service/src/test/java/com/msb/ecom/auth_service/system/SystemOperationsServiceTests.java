package com.msb.ecom.auth_service.system;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static com.msb.ecom.auth_service.system.SystemContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemOperationsServiceTests {
    private final AuthService auth = mock(AuthService.class);
    private final AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
    private final SystemOperationsClient client = mock(SystemOperationsClient.class);
    private final SystemOperationsRepository repository = mock(SystemOperationsRepository.class);
    private final UlidGenerator ids = mock(UlidGenerator.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final User actor = User.create("01ADMIN0000000000000000000", "subject", "ops@example.test",
            true, "Operations", "operations");
    private final Instant now = Instant.parse("2026-08-20T00:00:00Z");
    private SystemOperationsService service;

    @BeforeEach
    void setUp() {
        when(auth.ensureUserEntity()).thenReturn(actor);
        when(authorization.requirePermission(any(), any(AdminPermission.class))).thenReturn(actor.getId());
        service = new SystemOperationsService(auth, authorization, client, repository, ids,
                Clock.fixed(now, ZoneOffset.UTC), transactions);
    }

    @Test
    void summaryPreservesPartialHealthAndCountsOnlySafeOperationalSignals() {
        when(client.health()).thenReturn(List.of(
                new ServiceHealth("AUTH", "Auth", "HEALTHY", now, 10L, "Health endpoint responded normally."),
                new ServiceHealth("PAYMENT", "Payment", "UNAVAILABLE", now, 2000L,
                        "Health endpoint could not be reached within the bounded check.")));
        JobSummary failed = new JobSummary("job-1", "LISTING_SEARCH_PROJECTION", "PRODUCT",
                "FAILED", 2, 10, now, now, now, "correlation-1", "SAFE_CODE",
                "The worker recorded a safe code.", true, "LISTING", "listing-1");
        SourceSnapshot product = new SourceSnapshot("PRODUCT", true, "Signals available.",
                List.of(failed), List.of(), List.of(), List.of(),
                new SearchStatus("PRODUCT", "DEGRADED", 0, 1, null, "v1",
                        "One failed projection.", List.of()), List.of());
        when(client.snapshots()).thenReturn(List.of(product,
                SourceSnapshot.unavailable("PAYMENT", "Payment signals are temporarily unavailable.")));
        when(repository.recent(8)).thenReturn(List.of());

        SystemSummary summary = service.summary();

        assertThat(summary.serviceHealth()).isEqualTo(new CountPair(1, 1));
        assertThat(summary.jobs()).isEqualTo(new CountPair(1, 1));
        assertThat(summary.failedIndexOperations()).isEqualTo(1);
        assertThat(summary.sourceWarnings()).containsExactly("Payment signals are temporarily unavailable.");
        verify(authorization).requirePermission(actor, AdminPermission.SYSTEM_READ);
    }

    @Test
    void completedIdempotentCommandReplaysWithoutRequeryingAnOwnerService() throws Exception {
        String reason = "Retry after the transport dependency recovered";
        String requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("OUTBOX_EVENT|event-1|RETRY_OUTBOX|" + reason)
                        .getBytes(StandardCharsets.UTF_8)));
        when(repository.command(actor.getId(), "RETRY_OUTBOX", "operation-key-123", false))
                .thenReturn(Optional.of(new SystemOperationsRepository.CommandRow(
                        "command-1", requestHash, "PAYMENT", "OUTBOX_EVENT", "event-1", reason,
                        "COMPLETED", "ACCEPTED", "correlation-1", now)));

        MaintenanceResult result = service.execute("OUTBOX_EVENT", "event-1", "RETRY_OUTBOX",
                new MaintenanceRequest(reason, null, null), "operation-key-123", "correlation-1");

        assertThat(result.replay()).isTrue();
        assertThat(result.result()).isEqualTo("ACCEPTED");
        verifyNoInteractions(client);
        verify(authorization).requirePermission(actor, AdminPermission.SYSTEM_RETRY);
    }

    @Test
    void failureFiltersMatchTheSameBoundedStatusUnionsAsTheAnalyticsSummary() {
        List<JobSummary> jobs = List.of(
                job("failed", "FAILED"), job("dead", "DEAD_LETTER"),
                job("terminal", "TERMINAL"), job("pending", "PENDING"));
        List<OutboxSummary> outbox = List.of(
                outbox("failed", "FAILED"), outbox("dead", "DEAD_LETTER"),
                outbox("sent", "SENT"));
        when(client.snapshots()).thenReturn(List.of(new SourceSnapshot("PRODUCT", true,
                "Signals available.", jobs, outbox, List.of(), List.of(), null, List.of())));

        assertThat(service.jobs(null, null, "FAILURE", null, 0, 20).items())
                .extracting(JobSummary::jobId)
                .containsExactlyInAnyOrder("job-failed", "job-dead", "job-terminal");
        assertThat(service.outbox(null, null, "FAILURE", null, 0, 20).items())
                .extracting(OutboxSummary::eventId)
                .containsExactlyInAnyOrder("event-failed", "event-dead");
        assertThat(service.outbox(null, null, "DEAD_LETTER", null, 0, 20).items())
                .extracting(OutboxSummary::eventId)
                .containsExactly("event-dead");
    }

    private JobSummary job(String id, String status) {
        return new JobSummary("job-" + id, "TEST_JOB", "PRODUCT", status,
                1, 3, now, now, null, "correlation-" + id, null, null,
                false, "LISTING", "listing-" + id);
    }

    private OutboxSummary outbox(String id, String status) {
        return new OutboxSummary("event-" + id, "PRODUCT", "TEST_EVENT",
                "LISTING", "listing-" + id, status, 1, now, now, null,
                "correlation-" + id, null, null, false);
    }
}
