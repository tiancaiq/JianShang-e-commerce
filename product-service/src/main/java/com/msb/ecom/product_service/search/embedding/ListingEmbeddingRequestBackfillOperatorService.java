package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorAuditRepository;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ListingEmbeddingRequestBackfillOperatorService {
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private final ListingEmbeddingRequestBackfillProperties properties;
    private final CurrentActorProvider currentActorProvider;
    private final AuthServiceClient authServiceClient;
    private final ListingEmbeddingRequestBackfillProcessor processor;
    private final ListingEmbeddingRequestBackfillRepository repository;
    private final ListingSearchOperatorAuditRepository auditRepository;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    // Authenticates a platform admin before creating and processing one bounded page.
    public ListingEmbeddingRequestBackfillResponse start(String correlationId) {
        String adminUserId = requirePlatformAdmin();
        requireCommandsEnabled();
        ListingEmbeddingRequestBackfillRun before = repository.findActive().orElse(null);
        try {
            ListingEmbeddingRequestBackfillRun after = processor.start();
            audit(adminUserId, "EMBEDDING_START", before, after,
                    "SUCCEEDED", null, correlationId);
            return ListingEmbeddingRequestBackfillResponse.from(after, "SUCCEEDED");
        } catch (ListingEmbeddingRequestBackfillException exception) {
            ListingEmbeddingRequestBackfillRun after = repository.findActive().orElse(before);
            audit(adminUserId, "EMBEDDING_START", before, after,
                    "FAILED", exception.code(), correlationId);
            throw exception;
        } catch (RuntimeException exception) {
            ListingEmbeddingRequestBackfillRun after = repository.findActive().orElse(before);
            audit(adminUserId, "EMBEDDING_START", before, after,
                    "FAILED", "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE", correlationId);
            throw unavailable(exception);
        }
    }

    // Continues one bounded page and returns deterministic replay for a completed run.
    public ListingEmbeddingRequestBackfillResponse resume(
            String runId,
            String correlationId) {
        String adminUserId = requirePlatformAdmin();
        requireCommandsEnabled();
        String normalized = normalizedRunId(runId);
        ListingEmbeddingRequestBackfillRun before = processor.status(normalized);
        try {
            ListingEmbeddingRequestBackfillRun after = processor.resume(normalized);
            String outcome = before.completed() ? "REPLAYED" : "SUCCEEDED";
            audit(adminUserId, "EMBEDDING_RESUME", before, after,
                    outcome, null, correlationId);
            return ListingEmbeddingRequestBackfillResponse.from(after, outcome);
        } catch (ListingEmbeddingRequestBackfillException exception) {
            ListingEmbeddingRequestBackfillRun after = repository.findByRunId(normalized)
                    .orElse(before);
            audit(adminUserId, "EMBEDDING_RESUME", before, after,
                    "FAILED", exception.code(), correlationId);
            throw exception;
        } catch (RuntimeException exception) {
            ListingEmbeddingRequestBackfillRun after = repository.findByRunId(normalized)
                    .orElse(before);
            audit(adminUserId, "EMBEDDING_RESUME", before, after,
                    "FAILED", "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE", correlationId);
            throw unavailable(exception);
        }
    }

    // Returns the bounded status only after the independent read gate and admin authorization.
    public ListingEmbeddingRequestBackfillResponse status(String runId) {
        requirePlatformAdmin();
        if (!properties.statusEnabled()) {
            throw new ListingEmbeddingRequestBackfillException(
                    ListingEmbeddingRequestBackfillException.Kind.DISABLED,
                    "FEATURE_DISABLED");
        }
        return ListingEmbeddingRequestBackfillResponse.from(
                processor.status(normalizedRunId(runId)),
                "OBSERVED");
    }

    private String requirePlatformAdmin() {
        try {
            CurrentActor actor = currentActorProvider.currentActor();
            return authServiceClient.requirePlatformAdmin(actor.accessToken()).userId();
        } catch (ListingAuthorizationException | AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private void requireCommandsEnabled() {
        if (!properties.commandsEnabled()) {
            throw new ListingEmbeddingRequestBackfillException(
                    ListingEmbeddingRequestBackfillException.Kind.DISABLED,
                    "FEATURE_DISABLED");
        }
    }

    private String normalizedRunId(String runId) {
        if (runId == null || !ULID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Run ID is invalid.");
        }
        return runId;
    }

    private void audit(
            String adminUserId,
            String action,
            ListingEmbeddingRequestBackfillRun before,
            ListingEmbeddingRequestBackfillRun after,
            String outcome,
            String errorCode,
            String correlationId) {
        try {
            auditRepository.appendEmbeddingBackfill(
                    ulidGenerator.next(),
                    adminUserId,
                    action,
                    after != null ? after.runId() : before == null ? null : before.runId(),
                    before == null ? null : before.state(),
                    after == null ? null : after.state(),
                    outcome,
                    errorCode,
                    correlationId,
                    clock.instant());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private ListingEmbeddingRequestBackfillException unavailable(Throwable cause) {
        return new ListingEmbeddingRequestBackfillException(
                ListingEmbeddingRequestBackfillException.Kind.UNAVAILABLE,
                "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE",
                cause);
    }
}
