package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.enforcement.EnforcementService.TrustedAdminActor;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.EffectiveState;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Executor;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Outcome;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Replacement;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Request;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Response;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.security.AdminPermission;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ListingAppealResolutionService {
    private static final String APPEAL_RESOLVE = "admin.appeal.resolve";
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final EnforcementRepository repository;
    private final EnforcementService enforcementService;
    private final Clock clock;

    @Transactional
    public Response preview(String rawAppealId, Request raw) {
        return resolve(rawAppealId, raw, true);
    }

    @Transactional
    // Owns the single Product transaction for revoke or revoke-plus-replacement appeal execution.
    public Response execute(String rawAppealId, Request raw) {
        return resolve(rawAppealId, raw, false);
    }

    private Response resolve(String rawAppealId, Request raw, boolean dryRun) {
        Normalized command = normalize(rawAppealId, raw, dryRun);
        String fingerprint = fingerprint(command);
        if (!dryRun) {
            Response replay = replay(command, fingerprint, false);
            if (replay != null) return replay;
            if (command.recoveryOnly()) {
                throw new EnforcementExceptions.Conflict(
                        "No completed listing appeal resolution exists for recovery.");
            }
        }

        EnforcementRepository.Action initial = repository.action(command.enforcementActionId(), false)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        EnforcementRepository.Target target = repository.lockListing(initial.targetId())
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Listing target was not found."));
        List<EnforcementRepository.Action> lockedActions = repository.all(initial.targetId(), true);
        EnforcementRepository.Action original = lockedActions.stream()
                .filter(action -> action.id().equals(command.enforcementActionId())).findFirst()
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));

        if (!dryRun) {
            Response replay = replay(command, fingerprint, true);
            if (replay != null) return replay;
        }
        validateCurrent(command, target, original);
        String confirmationToken = confirmationToken(command, target, lockedActions);
        if (!dryRun && !constantTimeEquals(confirmationToken, command.confirmationToken())) {
            throw new EnforcementExceptions.Conflict(
                    "The listing enforcement state changed after the resolution preview.");
        }

        Instant executionTime = dryRun ? null : clock.instant();
        if (!dryRun && command.outcome() == Outcome.MODIFIED
                && command.replacement().expiresAt() != null
                && !command.replacement().expiresAt().isAfter(executionTime)) {
            throw new EnforcementExceptions.Validation(
                    "Replacement enforcement must not be expired at execution time.");
        }

        TrustedAdminActor actor = new TrustedAdminActor(command.executor().actorId(),
                command.executor().displayName(), command.executor().permissions());
        RevokeCommand revoke = command.outcome() == Outcome.UPHELD ? null
                : new RevokeCommand(original.id(), command.expectedEnforcementVersion(),
                command.reasonCode(), command.reason(), dryRun ? null : derivedKey(command.idempotencyKey(), "revoke"),
                command.safeMetadata(), dryRun);

        if (dryRun) {
            var revoked = revoke == null ? null : enforcementService.revokeTrusted(revoke, actor);
            List<EffectiveRestriction> effective = revoke == null
                    ? effective(original.targetId(), clock.instant()) : revoked.effectiveRestrictions();
            if (command.outcome() == Outcome.MODIFIED) {
                effective = enforcementService.previewReplacementAfterRevocation(
                        create(command, original, target.version(), null, true), original.id(), actor)
                        .effectiveRestrictions();
            }
            return response(command, original.id(), revoked == null ? original.version() : revoked.version(), null,
                    command.outcome() == Outcome.MODIFIED ? 0L : null, target.version(), effective,
                    confirmationToken, correlationId(), true, false);
        }

        Instant now = executionTime;
        String correlationId = correlationId();
        if (!repository.reserveAppealResolution(command.idempotencyKey(), command.appealId(),
                command.outcome().name(), fingerprint, original.id(), command.expectedEnforcementVersion(),
                command.expectedTargetVersion(), new EnforcementRepository.Actor(actor.userId(), actor.display()),
                correlationId, now)) {
            Response replay = replay(command, fingerprint, true);
            if (replay != null) return replay;
            if (repository.appealResolutionForAppeal(command.appealId(), true).isPresent()) {
                throw new EnforcementExceptions.Conflict("The appeal already has a listing enforcement resolution.");
            }
            throw new EnforcementExceptions.Conflict("The appeal enforcement resolution is already in progress.");
        }

        var revoked = revoke == null ? null : enforcementService.revokeTrusted(revoke, actor);
        String replacementId = null;
        Long replacementVersion = null;
        List<EffectiveRestriction> effective = revoked == null
                ? effective(original.targetId(), now) : revoked.effectiveRestrictions();
        if (command.outcome() == Outcome.MODIFIED) {
            var replacement = enforcementService.createTrusted(
                    create(command, original, target.version(),
                            derivedKey(command.idempotencyKey(), "replacement"), false), actor, original.id());
            replacementId = replacement.enforcementActionId();
            replacementVersion = replacement.version();
            effective = replacement.effectiveRestrictions();
        }
        repository.completeAppealResolution(command.idempotencyKey(), replacementId, clock.instant());
        return response(command, original.id(), revoked == null ? original.version() : revoked.version(),
                replacementId, replacementVersion,
                target.version(), effective, null, correlationId, false, false);
    }

    private CreateCommand create(Normalized command, EnforcementRepository.Action original,
            long targetVersion, String idempotencyKey, boolean dryRun) {
        Replacement replacement = command.replacement();
        return new CreateCommand(TargetType.LISTING, original.targetId(), replacement.actionType(),
                replacement.scopes(), replacement.reasonCode(), replacement.reason(), original.caseId(),
                replacement.effectiveAt(), replacement.expiresAt(), targetVersion, idempotencyKey,
                command.safeMetadata(), dryRun);
    }

    private Response replay(Normalized command, String fingerprint, boolean lock) {
        return repository.appealResolution(command.idempotencyKey(), lock).map(stored -> {
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new EnforcementExceptions.Conflict(
                        "The idempotency key was used with a different appeal resolution.");
            }
            if (stored.completedAt() == null) return null;
            EnforcementRepository.Action original = repository.action(stored.originalActionId(), lock)
                    .orElseThrow();
            EnforcementRepository.Target target = repository.listing(original.targetId()).orElseThrow();
            EnforcementRepository.Action replacement = stored.replacementActionId() == null ? null
                    : repository.action(stored.replacementActionId(), lock).orElseThrow();
            List<EffectiveRestriction> effective = effective(original.targetId(), clock.instant());
            return response(command, original.id(), original.version(),
                    replacement == null ? null : replacement.id(), replacement == null ? null : replacement.version(),
                    target.version(), effective, null, stored.correlationId(), false, true);
        }).orElse(null);
    }

    private void validateCurrent(Normalized command, EnforcementRepository.Target target,
            EnforcementRepository.Action original) {
        if (original.version() != command.expectedEnforcementVersion() || original.revokedAt() != null) {
            throw new EnforcementExceptions.Conflict(
                    "The enforcement action version is stale or already revoked.");
        }
        if (target.version() != command.expectedTargetVersion()) {
            throw new EnforcementExceptions.Conflict("The listing version is stale.");
        }
        if (command.outcome() == Outcome.MODIFIED && !"ACTIVE".equals(target.status())) {
            throw new EnforcementExceptions.Validation(
                    "Only active listings can receive replacement enforcement.");
        }
    }

    private Normalized normalize(String rawAppealId, Request request, boolean dryRun) {
        String appealId = FixedLengthIds.requireTrimmed("Appeal ID", rawAppealId, 26);
        if (request == null || request.outcome() == null) {
            throw new EnforcementExceptions.Validation("Appeal enforcement outcome is required.");
        }
        String actionId = FixedLengthIds.requireTrimmed(
                "Enforcement action ID", request.enforcementActionId(), 26);
        if (request.expectedEnforcementVersion() == null || request.expectedEnforcementVersion() < 0
                || request.expectedTargetVersion() == null || request.expectedTargetVersion() < 0) {
            throw new EnforcementExceptions.Validation("Expected enforcement and listing versions are required.");
        }
        String reasonCode = text("Reason code", request.reasonCode(), 64).toUpperCase(Locale.ROOT);
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new EnforcementExceptions.Validation(
                    "Reason code must use stable uppercase identifiers.");
        }
        String reason = text("Reason", request.reason(), 1000);
        Executor executor = normalize(request.executor());
        requirePermission(executor, APPEAL_RESOLVE);
        if (request.outcome() != Outcome.UPHELD) {
            requirePermission(executor, AdminPermission.LISTING_REINSTATE.id());
        }
        Replacement replacement = request.replacement();
        if (request.outcome() == Outcome.MODIFIED) {
            requirePermission(executor, AdminPermission.LISTING_SUSPEND.id());
            if (replacement == null || replacement.actionType() == null
                    || replacement.scopes() == null || replacement.scopes().isEmpty()) {
                throw new EnforcementExceptions.Validation("Replacement enforcement is required.");
            }
            String replacementReasonCode = text("Replacement reason code", replacement.reasonCode(), 64)
                    .toUpperCase(Locale.ROOT);
            if (!REASON_CODE.matcher(replacementReasonCode).matches()) {
                throw new EnforcementExceptions.Validation(
                        "Replacement reason code must use stable uppercase identifiers.");
            }
            replacement = new Replacement(replacement.actionType(), Set.copyOf(replacement.scopes()),
                    replacement.effectiveAt(), replacement.expiresAt(), replacementReasonCode,
                    text("Replacement reason", replacement.reason(), 1000));
        } else if (replacement != null) {
            throw new EnforcementExceptions.Validation(
                    "Replacement enforcement is allowed only for a modified appeal.");
        }
        String idempotencyKey = dryRun ? optionalText(request.idempotencyKey(), 128)
                : text("Idempotency key", request.idempotencyKey(), 128);
        if (!dryRun && !Boolean.TRUE.equals(request.confirmed())) {
            throw new EnforcementExceptions.Validation("Explicit resolution confirmation is required.");
        }
        if (!dryRun && (request.confirmationToken() == null || request.confirmationToken().isBlank())) {
            throw new EnforcementExceptions.Validation("Resolution confirmation token is required.");
        }
        Map<String, String> metadata = request.safeMetadata() == null
                ? Map.of() : Map.copyOf(request.safeMetadata());
        return new Normalized(appealId, request.outcome(), actionId,
                request.expectedEnforcementVersion(), request.expectedTargetVersion(), reasonCode, reason,
                replacement, idempotencyKey, executor, metadata,
                request.confirmationToken() == null ? null : request.confirmationToken().trim(),
                Boolean.TRUE.equals(request.recoveryOnly()));
    }

    private Executor normalize(Executor executor) {
        if (executor == null) {
            throw new ListingAuthorizationException("Appeal resolution executor is required.");
        }
        String actorId = FixedLengthIds.requireTrimmed("Executor ID", executor.actorId(), 26);
        String display = text("Executor display name", executor.displayName(), 200);
        Set<String> permissions = executor.permissions() == null ? Set.of() : Set.copyOf(executor.permissions());
        return new Executor(actorId, display, permissions);
    }

    private void requirePermission(Executor executor, String permission) {
        if (!executor.permissions().contains(permission)) {
            throw new ListingAuthorizationException(
                    "Appeal resolution requires permission " + permission + ".");
        }
    }

    private Response response(Normalized command, String originalId, long originalVersion,
            String replacementId, Long replacementVersion, long listingVersion,
            List<EffectiveRestriction> effective, String confirmationToken, String correlationId,
            boolean dryRun, boolean replayed) {
        EffectiveState state = state(effective);
        boolean overlappingActionRemains = command.outcome() == Outcome.UPHELD ? false
                : command.outcome() == Outcome.REVOKED ? !effective.isEmpty()
                : effective.stream().anyMatch(value -> value.enforcementActionId() != null
                        && !value.enforcementActionId().equals(replacementId));
        List<String> warnings = !overlappingActionRemains ? List.of()
                : List.of("Another listing enforcement remains effective after this resolution.");
        List<String> impact = command.outcome() == Outcome.UPHELD
                ? List.of("The original listing enforcement will remain unchanged.",
                        "The listing lifecycle and moderation history are unchanged.") : switch (state) {
            case CLEAR -> List.of("No listing-level enforcement remains effective.",
                    "The listing lifecycle and moderation history are unchanged.");
            case RESTRICTED -> List.of("A listing restriction remains effective.",
                    "The original enforcement history is preserved.");
            case SUSPENDED -> List.of("A listing suspension remains effective.",
                    "The original enforcement history is preserved.");
        };
        return new Response(command.appealId(), command.outcome(), originalId, originalVersion,
                replacementId, replacementVersion, listingVersion, List.copyOf(effective), state,
                impact, warnings, confirmationToken, correlationId, dryRun, replayed);
    }

    private EffectiveState state(List<EffectiveRestriction> effective) {
        if (effective == null || effective.isEmpty()) return EffectiveState.CLEAR;
        return effective.stream().anyMatch(value -> value.actionType() == ActionType.SUSPEND)
                ? EffectiveState.SUSPENDED : EffectiveState.RESTRICTED;
    }

    private List<EffectiveRestriction> effective(String targetId, Instant now) {
        EffectiveEnforcementEvaluator evaluator = new EffectiveEnforcementEvaluator();
        return evaluator.evaluate(repository.active(targetId, now).stream()
                .map(action -> new EffectiveEnforcementEvaluator.Candidate(action.id(), action.actionType(),
                        action.scopes(), action.effectiveAt(), action.expiresAt(), action.revokedAt()))
                .toList(), now);
    }

    private String confirmationToken(Normalized command, EnforcementRepository.Target target,
            List<EnforcementRepository.Action> actions) {
        Instant now = clock.instant();
        String state = actions.stream().sorted(Comparator.comparing(EnforcementRepository.Action::id))
                .map(action -> String.join(":", action.id(), Long.toString(action.version()),
                        lifecycle(action, now), value(action.effectiveAt()), value(action.expiresAt())))
                .reduce((left, right) -> left + ";" + right).orElse("");
        return hash("PREVIEW|" + fingerprint(command) + "|" + target.status() + "|"
                + target.version() + "|" + state);
    }

    private String lifecycle(EnforcementRepository.Action action, Instant now) {
        if (action.revokedAt() != null) return "REVOKED";
        return action.expiresAt() != null && !action.expiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
    }

    private String fingerprint(Normalized command) {
        Replacement replacement = command.replacement();
        String replacementText = replacement == null ? "" : String.join("|",
                replacement.actionType() == null ? "" : replacement.actionType().name(),
                replacement.scopes() == null ? "" : replacement.scopes().stream().map(Enum::name)
                        .sorted().reduce((left, right) -> left + "," + right).orElse(""),
                value(replacement.effectiveAt()), value(replacement.expiresAt()),
                value(replacement.reasonCode()), value(replacement.reason()));
        return hash(String.join("|", command.appealId(), command.outcome().name(),
                command.enforcementActionId(), Long.toString(command.expectedEnforcementVersion()),
                Long.toString(command.expectedTargetVersion()), command.reasonCode(), command.reason(),
                replacementText, command.executor().actorId(), metadata(command.safeMetadata())));
    }

    private String derivedKey(String key, String operation) {
        return hash("APPEAL_LISTING_RESOLUTION|" + key + "|" + operation);
    }

    private String metadata(Map<String, String> metadata) {
        LinkedHashMap<String, String> sorted = new LinkedHashMap<>();
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private String text(String label, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new EnforcementExceptions.Validation(label + " is required.");
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > max) {
            throw new EnforcementExceptions.Validation(label + " is too long.");
        }
        return normalized;
    }

    private String optionalText(String value, int max) {
        return value == null || value.isBlank() ? null : text("Idempotency key", value, max);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? CorrelationId.generate().value() : value;
    }

    private String value(Object value) { return value == null ? "" : value.toString(); }

    private record Normalized(String appealId, Outcome outcome, String enforcementActionId,
            long expectedEnforcementVersion, long expectedTargetVersion,
            String reasonCode, String reason, Replacement replacement, String idempotencyKey,
            Executor executor, Map<String, String> safeMetadata, String confirmationToken,
            boolean recoveryOnly) { }
}
