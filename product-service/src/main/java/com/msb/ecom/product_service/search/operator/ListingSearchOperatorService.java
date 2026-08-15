package com.msb.ecom.product_service.search.operator;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.ListingSearchPromotionService;
import com.msb.ecom.product_service.search.ListingSearchPromotionEligibility;
import com.msb.ecom.product_service.search.ListingSearchRebuildRun;
import com.msb.ecom.product_service.search.ListingSearchUnavailableException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ListingSearchOperatorService {
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private final ListingSearchOperatorProperties properties;
    private final CurrentActorProvider currentActorProvider;
    private final AuthServiceClient authServiceClient;
    private final ListingSearchPromotionService promotionService;
    private final ListingSearchOperatorAuditRepository auditRepository;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    // Authenticates first, then creates exactly one durable inactive V2 rebuild run.
    public ListingSearchOperatorResponse prepare(String correlationId) {
        String adminUserId = requirePlatformAdmin();
        requireCommandsEnabled();
        Optional<ListingSearchRebuildRun> active = promotionService.findActiveRun();
        if (active.isPresent()) {
            audit(adminUserId, "PREPARE", active.get(), active.get(),
                    "FAILED", "VECTOR_REBUILD_ACTIVE", correlationId);
            throw new ListingSearchOperatorConflictException("VECTOR_REBUILD_ACTIVE");
        }
        try {
            ListingSearchRebuildRun run = promotionService.prepareInactiveCandidate();
            audit(adminUserId, "PREPARE", null, run,
                    "SUCCEEDED", null, correlationId);
            return response(run, "SUCCEEDED");
        } catch (ListingSearchUnavailableException exception) {
            audit(adminUserId, "PREPARE", null, null,
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        } catch (RuntimeException exception) {
            audit(adminUserId, "PREPARE", null, safeActive(),
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }

    // Returns only the bounded durable run projection after admin and read-gate checks.
    public ListingSearchOperatorResponse status(String runId) {
        requirePlatformAdmin();
        if (!properties.statusEnabled()) {
            throw new ListingSearchOperatorFeatureDisabledException();
        }
        ListingSearchRebuildRun run = findRun(normalizedRunId(runId));
        return response(run, "OBSERVED");
    }

    // Replays bounded catch-up while preserving the P0-05B durable state machine.
    public ListingSearchOperatorResponse catchUp(String runId, String correlationId) {
        return command(runId, correlationId, "CATCH_UP", false);
    }

    // Executes the existing exclusive-fence promotion with deterministic promoted replay.
    public ListingSearchOperatorResponse promote(String runId, String correlationId) {
        return command(runId, correlationId, "PROMOTE", true);
    }

    // Reconciles durable state against exact aliases without accepting client topology input.
    public ListingSearchOperatorResponse recover(String runId, String correlationId) {
        String adminUserId = requirePlatformAdmin();
        requireCommandsEnabled();
        String normalized = normalizedRunId(runId);
        ListingSearchRebuildRun before = findRun(normalized);
        if (!promotionService.commandEligibility(before).canRecover()) {
            audit(adminUserId, "RECOVER", before, before,
                    "FAILED", "VECTOR_REBUILD_STATE_CONFLICT", correlationId);
            throw new ListingSearchOperatorConflictException(
                    "VECTOR_REBUILD_STATE_CONFLICT");
        }
        try {
            ListingSearchRebuildRun after = promotionService.recover(normalized);
            String outcome = sameState(before, after) ? "REPLAYED" : "SUCCEEDED";
            audit(adminUserId, "RECOVER", before, after, outcome, null, correlationId);
            return response(after, outcome);
        } catch (ListingSearchUnavailableException exception) {
            audit(adminUserId, "RECOVER", before, safeFind(normalized),
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        } catch (RuntimeException exception) {
            audit(adminUserId, "RECOVER", before, safeFind(normalized),
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }

    private ListingSearchOperatorResponse command(
            String runId,
            String correlationId,
            String action,
            boolean promotedReplayAllowed) {
        String adminUserId = requirePlatformAdmin();
        requireCommandsEnabled();
        String normalized = normalizedRunId(runId);
        ListingSearchRebuildRun before = findRun(normalized);
        if (promotedReplayAllowed && "PROMOTED".equals(before.state())) {
            audit(adminUserId, action, before, before, "REPLAYED", null, correlationId);
            return response(before, "REPLAYED");
        }
        ListingSearchPromotionEligibility eligibility =
                promotionService.commandEligibility(before);
        boolean allowed = "PROMOTE".equals(action)
                ? eligibility.canPromote()
                : eligibility.canCatchUp();
        if (!allowed) {
            audit(adminUserId, action, before, before,
                    "FAILED", "VECTOR_REBUILD_STATE_CONFLICT", correlationId);
            throw new ListingSearchOperatorConflictException(
                    "VECTOR_REBUILD_STATE_CONFLICT");
        }
        try {
            ListingSearchRebuildRun after;
            if ("PROMOTE".equals(action)) {
                after = promotionService.promote(normalized);
            } else {
                promotionService.catchUp(normalized);
                after = findRun(normalized);
            }
            String outcome = sameState(before, after) && "CATCH_UP".equals(action)
                    ? "REPLAYED" : "SUCCEEDED";
            audit(adminUserId, action, before, after, outcome, null, correlationId);
            return response(after, outcome);
        } catch (ListingSearchUnavailableException exception) {
            audit(adminUserId, action, before, safeFind(normalized),
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        } catch (RuntimeException exception) {
            audit(adminUserId, action, before, safeFind(normalized),
                    "FAILED", "VECTOR_REBUILD_UNAVAILABLE", correlationId);
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }

    private String requirePlatformAdmin() {
        try {
            CurrentActor actor = currentActorProvider.currentActor();
            return authServiceClient.requirePlatformAdmin(actor.accessToken()).userId();
        } catch (ListingAuthorizationException | AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }

    private void requireCommandsEnabled() {
        if (!properties.commandsEnabled()) {
            throw new ListingSearchOperatorFeatureDisabledException();
        }
    }

    private ListingSearchRebuildRun findRun(String runId) {
        return promotionService.findRun(runId)
                .orElseThrow(ListingSearchOperatorNotFoundException::new);
    }

    private ListingSearchRebuildRun safeFind(String runId) {
        return promotionService.findRun(runId).orElse(null);
    }

    private ListingSearchRebuildRun safeActive() {
        return promotionService.findActiveRun().orElse(null);
    }

    private ListingSearchOperatorResponse response(
            ListingSearchRebuildRun run,
            String outcome) {
        try {
            ListingSearchPromotionEligibility eligibility =
                    properties.commandsEnabled()
                            ? promotionService.commandEligibility(run)
                            : ListingSearchPromotionEligibility.NONE;
            return ListingSearchOperatorResponse.from(
                    run,
                    outcome,
                    eligibility);
        } catch (ListingSearchOperatorUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }

    private String normalizedRunId(String runId) {
        if (runId == null || !ULID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Run ID is invalid.");
        }
        return runId;
    }

    private boolean sameState(ListingSearchRebuildRun before, ListingSearchRebuildRun after) {
        return before.state().equals(after.state())
                && before.catchUpWorkCount() == after.catchUpWorkCount()
                && before.updatedAt().equals(after.updatedAt());
    }

    private void audit(
            String adminUserId,
            String action,
            ListingSearchRebuildRun before,
            ListingSearchRebuildRun after,
            String outcome,
            String errorCode,
            String correlationId) {
        try {
            auditRepository.append(
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
            throw new ListingSearchOperatorUnavailableException(exception);
        }
    }
}
