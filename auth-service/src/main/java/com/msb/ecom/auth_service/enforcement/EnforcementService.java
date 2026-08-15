package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Source;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TimelineEntry;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EnforcementService {

    private final EnforcementRepository repository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AdminAuthorizationService authorizationService;
    private final UlidGenerator ulidGenerator;
    private final Clock clock = Clock.systemUTC();
    private final EnforcementPolicy policy = new EnforcementPolicy();
    private final EffectiveEnforcementEvaluator evaluator = new EffectiveEnforcementEvaluator();

    @Transactional
    // Creates an Auth-owned enforcement record and immutable CREATED event; consumers
    // derive runtime capability decisions from the committed action rather than cascades.
    public Result create(CreateCommand rawCommand) {
        User actor = authService.ensureUserEntity();
        Instant now = clock.instant();
        EnforcementPolicy.NormalizedCreate command = policy.normalize(rawCommand, now);
        authorizationService.requirePermission(actor, permission(command.targetType(), command.actionType()));
        String fingerprint = policy.fingerprint(command);

        if (!command.dryRun()) {
            Result replay = replay("CREATE", command.idempotencyKey(), fingerprint, now);
            if (replay != null) {
                return replay;
            }
        }

        long targetVersion = lockAndValidateTarget(command);
        if (!command.dryRun()) {
            Result replayAfterTargetLock = replayLocked("CREATE", command.idempotencyKey(), fingerprint, now);
            if (replayAfterTargetLock != null) {
                return replayAfterTargetLock;
            }
        }
        rejectExactActiveDuplicate(command, now);
        if (command.dryRun()) {
            return dryRunCreate(command, targetVersion, now);
        }

        if (!repository.reserveIdempotency("CREATE", command.idempotencyKey(), fingerprint, now)) {
            Result replay = replayLocked("CREATE", command.idempotencyKey(), fingerprint, now);
            if (replay != null) {
                return replay;
            }
            throw new EnforcementExceptions.Conflict("The enforcement command is already in progress.");
        }

        String actionId = ulidGenerator.next();
        String requestId = ulidGenerator.next();
        String eventRequestId = ulidGenerator.next();
        String correlationId = correlationId();
        EnforcementRepository.Actor trustedActor = actor(actor);
        EnforcementRepository.Action action = new EnforcementRepository.Action(
                actionId, command.targetType(), command.targetId(), command.actionType(), command.scopes(),
                0, command.effectiveAt(), command.expiresAt(), command.reasonCode(), command.reason(),
                command.caseId(), targetVersion, Source.HUMAN_ADMIN, trustedActor.id(), trustedActor.displayName(),
                now, null, correlationId, requestId, command.idempotencyKey(), fingerprint, null, null,
                command.safeMetadata());
        repository.insertAction(action);
        repository.insertEvent(new EnforcementRepository.Event(
                ulidGenerator.next(), actionId, "CREATED", null, lifecycle(action, now).name(), now,
                "PLATFORM_ADMIN", trustedActor.id(), trustedActor.displayName(), Source.HUMAN_ADMIN,
                command.reasonCode(), command.reason(), correlationId, eventRequestId, command.safeMetadata()));
        repository.completeIdempotency("CREATE", command.idempotencyKey(), actionId, now);
        return result(action, now, false);
    }

    @Transactional
    // Revokes one recorded action using its own optimistic version and preserves the original row and scopes.
    public Result revoke(RevokeCommand rawCommand) {
        User actor = authService.ensureUserEntity();
        EnforcementPolicy.NormalizedRevoke command = policy.normalize(rawCommand);
        EnforcementRepository.Action initial = repository.findAction(command.enforcementActionId(), false)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        authorizationService.requirePermission(actor, reinstatePermission(initial.targetType()));
        String fingerprint = policy.fingerprint(command);
        Instant now = clock.instant();

        if (!command.dryRun()) {
            Result replay = replay("REVOKE", command.idempotencyKey(), fingerprint, now);
            if (replay != null) {
                return replay;
            }
        }

        EnforcementRepository.Action action = repository.findAction(command.enforcementActionId(), true)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        if (!command.dryRun()) {
            Result replayAfterActionLock = replayLocked("REVOKE", command.idempotencyKey(), fingerprint, now);
            if (replayAfterActionLock != null) {
                return replayAfterActionLock;
            }
        }
        validateRevocation(action, command);
        if (command.dryRun()) {
            return new Result(action.id(), action.targetType(), action.targetId(), action.actionType(), action.scopes(),
                    LifecycleState.REVOKED, action.effectiveAt(), action.expiresAt(), action.version() + 1,
                    action.createdAt(), now, action.reasonCode(), action.reason(),
                    effective(action.targetType(), action.targetId(), now, action.id()),
                    action.correlationId(), true);
        }

        if (!repository.reserveIdempotency("REVOKE", command.idempotencyKey(), fingerprint, now)) {
            Result replay = replayLocked("REVOKE", command.idempotencyKey(), fingerprint, now);
            if (replay != null) {
                return replay;
            }
            throw new EnforcementExceptions.Conflict("The enforcement command is already in progress.");
        }
        EnforcementRepository.Actor trustedActor = actor(actor);
        repository.revoke(action, command, trustedActor, fingerprint, now);
        String correlationId = correlationId();
        repository.insertEvent(new EnforcementRepository.Event(
                ulidGenerator.next(), action.id(), "REVOKED", lifecycle(action, now).name(), "REVOKED", now,
                "PLATFORM_ADMIN", trustedActor.id(), trustedActor.displayName(), Source.HUMAN_ADMIN,
                command.reasonCode(), command.reason(), correlationId, ulidGenerator.next(), command.safeMetadata()));
        repository.completeIdempotency("REVOKE", command.idempotencyKey(), action.id(), now);
        return result(repository.findAction(action.id(), false).orElseThrow(), now, false);
    }

    @Transactional(readOnly = true)
    public List<EffectiveRestriction> evaluate(TargetType targetType, String targetId, Instant at) {
        if (targetType == TargetType.LISTING) {
            throw new EnforcementExceptions.Validation("Auth Service owns only USER and BUSINESS enforcement.");
        }
        return effective(targetType, targetId, at == null ? clock.instant() : at, null);
    }

    @Transactional(readOnly = true)
    public List<Result> actions(TargetType targetType, String targetId) {
        if (targetType == TargetType.LISTING) {
            throw new EnforcementExceptions.Validation("Listing enforcement is Product-owned.");
        }
        Instant now = clock.instant();
        return repository.actions(targetType, targetId).stream()
                .map(action -> result(action, now, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(TargetType targetType, String targetId) {
        User actor = authService.ensureUserEntity();
        authorizationService.requirePermission(actor, AdminPermission.AUDIT_READ);
        return repository.timeline(targetType, targetId).stream().map(row -> new TimelineEntry(
                row.eventId(), row.occurredAt(), row.eventType(), row.actorType(), row.actorId(),
                row.actorDisplayName(), row.source(), row.targetType(), row.targetId(), row.actionId(), row.caseId(),
                row.previousState(), row.newState(), row.actionType(), repository.scopes(row.actionId()),
                row.reasonCode(), row.reason(), row.correlationId(), row.requestId(), row.safeMetadata())).toList();
    }

    private long lockAndValidateTarget(EnforcementPolicy.NormalizedCreate command) {
        long actual;
        if (command.targetType() == TargetType.USER) {
            User target = userRepository.lockById(command.targetId())
                    .orElseThrow(() -> new EnforcementExceptions.NotFound("User target was not found."));
            actual = target.getVersion();
        } else {
            actual = repository.lockBusiness(command.targetId())
                    .orElseThrow(() -> new EnforcementExceptions.NotFound("Business target was not found."))
                    .version();
        }
        if (actual != command.expectedTargetVersion()) {
            throw new EnforcementExceptions.Conflict("The target version is stale.");
        }
        return actual;
    }

    private void rejectExactActiveDuplicate(EnforcementPolicy.NormalizedCreate command, Instant now) {
        boolean duplicate = repository.activeActions(command.targetType(), command.targetId(), now).stream()
                .anyMatch(action -> action.actionType() == command.actionType()
                        && action.scopes().equals(command.scopes()));
        if (duplicate) {
            throw new EnforcementExceptions.Conflict("An equivalent active enforcement action already exists.");
        }
    }

    private Result replay(String type, String key, String fingerprint, Instant now) {
        return repository.findIdempotency(type, key, false)
                .map(value -> replay(value, fingerprint, now, false)).orElse(null);
    }

    private Result replayLocked(String type, String key, String fingerprint, Instant now) {
        return repository.findIdempotency(type, key, true)
                .map(value -> replay(value, fingerprint, now, true)).orElse(null);
    }

    private Result replay(EnforcementRepository.Idempotency value, String fingerprint, Instant now,
                          boolean lockAction) {
        if (!value.fingerprint().equals(fingerprint)) {
            throw new EnforcementExceptions.Conflict("The idempotency key was used with a different command.");
        }
        if (value.actionId() == null) {
            return null;
        }
        return result(repository.findAction(value.actionId(), lockAction).orElseThrow(), now, false);
    }

    private void validateRevocation(EnforcementRepository.Action action,
                                    EnforcementPolicy.NormalizedRevoke command) {
        if (action.version() != command.expectedEnforcementVersion()) {
            throw new EnforcementExceptions.Conflict("The enforcement action version is stale.");
        }
        if (action.revokedAt() != null) {
            throw new EnforcementExceptions.Conflict("The enforcement action is already revoked.");
        }
    }

    private Result dryRunCreate(EnforcementPolicy.NormalizedCreate command, long targetVersion, Instant now) {
        EnforcementRepository.Action proposal = new EnforcementRepository.Action(
                null, command.targetType(), command.targetId(), command.actionType(), command.scopes(), 0,
                command.effectiveAt(), command.expiresAt(), command.reasonCode(), command.reason(), command.caseId(),
                targetVersion, Source.HUMAN_ADMIN, null, null, now, null, correlationId(), null, null, null,
                null, null, command.safeMetadata());
        return new Result(null, command.targetType(), command.targetId(), command.actionType(), command.scopes(),
                lifecycle(proposal, now), command.effectiveAt(), command.expiresAt(), 0, now, null,
                command.reasonCode(), command.reason(), proposedEffective(proposal, now), proposal.correlationId(), true);
    }

    private Result result(EnforcementRepository.Action action, Instant now, boolean dryRun) {
        return new Result(action.id(), action.targetType(), action.targetId(), action.actionType(), action.scopes(),
                lifecycle(action, now), action.effectiveAt(), action.expiresAt(), action.version(),
                action.createdAt(), action.revokedAt(), action.reasonCode(), action.reason(),
                effective(action.targetType(), action.targetId(), now, null),
                action.correlationId(), dryRun);
    }

    private List<EffectiveRestriction> proposedEffective(EnforcementRepository.Action proposal, Instant now) {
        List<EnforcementRepository.Action> actions = new ArrayList<>(
                repository.activeActions(proposal.targetType(), proposal.targetId(), now));
        if (lifecycle(proposal, now) == LifecycleState.ACTIVE) {
            actions.add(proposal);
        }
        return selectEffective(actions, now);
    }

    private List<EffectiveRestriction> effective(TargetType targetType, String targetId, Instant now, String excludedId) {
        return selectEffective(repository.activeActions(targetType, targetId, now).stream()
                .filter(action -> excludedId == null || !excludedId.equals(action.id())).toList(), now);
    }

    private List<EffectiveRestriction> selectEffective(List<EnforcementRepository.Action> actions, Instant at) {
        return evaluator.evaluate(actions.stream().map(action -> new EffectiveEnforcementEvaluator.Candidate(
                action.id(), action.actionType(), action.scopes(), action.effectiveAt(), action.expiresAt(),
                action.revokedAt())).toList(), at);
    }

    private LifecycleState lifecycle(EnforcementRepository.Action action, Instant now) {
        if (action.revokedAt() != null) {
            return LifecycleState.REVOKED;
        }
        if (action.expiresAt() != null && !action.expiresAt().isAfter(now)) {
            return LifecycleState.EXPIRED;
        }
        return LifecycleState.ACTIVE;
    }

    private AdminPermission permission(TargetType targetType, ActionType actionType) {
        return switch (targetType) {
            case USER -> switch (actionType) {
                case RESTRICT -> AdminPermission.USER_RESTRICT;
                case SUSPEND -> AdminPermission.USER_SUSPEND;
                case BAN -> AdminPermission.USER_BAN;
            };
            case BUSINESS -> switch (actionType) {
                case RESTRICT -> AdminPermission.BUSINESS_RESTRICT;
                case SUSPEND -> AdminPermission.BUSINESS_SUSPEND;
                case BAN -> AdminPermission.BUSINESS_BAN;
            };
            case LISTING -> throw new EnforcementExceptions.Validation("Listing enforcement is Product-owned.");
        };
    }

    private AdminPermission reinstatePermission(TargetType targetType) {
        return switch (targetType) {
            case USER -> AdminPermission.USER_REINSTATE;
            case BUSINESS -> AdminPermission.BUSINESS_REINSTATE;
            case LISTING -> throw new EnforcementExceptions.Validation("Listing enforcement is Product-owned.");
        };
    }

    private EnforcementRepository.Actor actor(User user) {
        String display = user.getDisplayName();
        return new EnforcementRepository.Actor(user.getId(),
                display == null || display.isBlank() ? "Platform administrator" : display.trim());
    }

    private String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? CorrelationId.generate().value() : value;
    }
}
