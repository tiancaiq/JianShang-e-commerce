package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Source;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TimelineEntry;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.ActionView;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.AdminCapabilities;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.CapabilityDecision;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.Detail;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.EvaluateBatchResponse;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.ListingCapabilityDecision;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.Preview;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EnforcementService {
    private final EnforcementRepository repository;
    private final CurrentActorProvider currentActorProvider;
    private final AuthServiceClient authServiceClient;
    private final UlidGenerator ulidGenerator;
    private final Clock clock = Clock.systemUTC();
    private final EnforcementPolicy policy = new EnforcementPolicy();
    private final EffectiveEnforcementEvaluator evaluator = new EffectiveEnforcementEvaluator();

    @Transactional
    // Records Product-owned listing enforcement without rewriting listing moderation or lifecycle state.
    public Result create(CreateCommand raw) {
        return createTrusted(raw, require(AdminPermission.LISTING_SUSPEND), null);
    }

    @Transactional
    Result createTrusted(CreateCommand raw, TrustedAdminActor actor, String parentActionId) {
        actor.require(AdminPermission.LISTING_SUSPEND);
        Instant now = clock.instant();
        EnforcementPolicy.NormalizedCreate command = policy.normalize(raw, now);
        String fingerprint = policy.fingerprint(command);
        if (!command.dryRun()) {
            Result replay = replay("CREATE", command.idempotencyKey(), fingerprint, now, false);
            if (replay != null) return replay;
        }
        EnforcementRepository.Target target = repository.lockListing(command.targetId())
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Listing target was not found."));
        if ("REMOVED_BY_ADMIN".equals(target.status())) {
            throw new EnforcementExceptions.Validation(
                    "Administratively removed listings cannot receive temporary enforcement.");
        }
        if (!"ACTIVE".equals(target.status())) {
            throw new EnforcementExceptions.Validation("Only active listings can receive temporary enforcement.");
        }
        long version = target.version();
        if (version != command.expectedTargetVersion()) {
            throw new EnforcementExceptions.Conflict("The listing version is stale.");
        }
        if (!command.dryRun()) {
            Result replayAfterTargetLock = replay("CREATE", command.idempotencyKey(), fingerprint, now, true);
            if (replayAfterTargetLock != null) return replayAfterTargetLock;
        }
        boolean duplicate = repository.active(command.targetId(), now).stream()
                .anyMatch(action -> action.actionType() == command.actionType()
                        && action.scopes().equals(command.scopes()));
        if (duplicate) throw new EnforcementExceptions.Conflict("An equivalent active enforcement action exists.");
        if (command.dryRun()) return dryRun(command, version, now);
        if (!repository.reserve("CREATE", command.idempotencyKey(), fingerprint, now)) {
            Result replay = replay("CREATE", command.idempotencyKey(), fingerprint, now, true);
            if (replay != null) return replay;
            throw new EnforcementExceptions.Conflict("The enforcement command is already in progress.");
        }
        String actionId = ulidGenerator.next();
        String correlationId = correlationId();
        EnforcementRepository.Action action = new EnforcementRepository.Action(
                actionId, command.targetId(), command.actionType(), command.scopes(), 0, command.effectiveAt(),
                command.expiresAt(), command.reasonCode(), command.reason(), command.caseId(), version,
                actor.userId(), actor.display(), now, null, correlationId, ulidGenerator.next(),
                command.idempotencyKey(), fingerprint, command.safeMetadata());
        if (parentActionId == null) repository.insert(action);
        else repository.insert(action, parentActionId);
        repository.event(new EnforcementRepository.Event(ulidGenerator.next(), actionId, "CREATED", null,
                lifecycle(action, now).name(), now, actor.userId(), actor.display(), command.reasonCode(),
                command.reason(), correlationId, ulidGenerator.next(), command.safeMetadata()));
        repository.complete("CREATE", command.idempotencyKey(), actionId, now);
        return result(action, now, false);
    }

    @Transactional
    // Revokes a listing enforcement action with compare-and-set versioning and immutable history.
    public Result revoke(RevokeCommand raw) {
        return revokeTrusted(raw, require(AdminPermission.LISTING_REINSTATE));
    }

    @Transactional
    Result revokeTrusted(RevokeCommand raw, TrustedAdminActor actor) {
        actor.require(AdminPermission.LISTING_REINSTATE);
        EnforcementPolicy.NormalizedRevoke command = policy.normalize(raw);
        String fingerprint = policy.fingerprint(command);
        Instant now = clock.instant();
        if (!command.dryRun()) {
            Result replay = replay("REVOKE", command.idempotencyKey(), fingerprint, now, false);
            if (replay != null) return replay;
        }
        EnforcementRepository.Action initial = repository.action(command.enforcementActionId(), false)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        repository.lockListing(initial.targetId())
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Listing target was not found."));
        EnforcementRepository.Action action = repository.action(command.enforcementActionId(), true)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        if (!command.dryRun()) {
            Result replayAfterActionLock = replay("REVOKE", command.idempotencyKey(), fingerprint, now, true);
            if (replayAfterActionLock != null) return replayAfterActionLock;
        }
        if (action.version() != command.expectedEnforcementVersion() || action.revokedAt() != null) {
            throw new EnforcementExceptions.Conflict("The enforcement action version is stale or already revoked.");
        }
        if (command.dryRun()) {
            return new Result(action.id(), TargetType.LISTING, action.targetId(), action.actionType(), action.scopes(),
                    LifecycleState.REVOKED, action.effectiveAt(), action.expiresAt(), action.version() + 1,
                    action.createdAt(), now, effective(action.targetId(), now, action.id()), action.correlationId(), true);
        }
        if (!repository.reserve("REVOKE", command.idempotencyKey(), fingerprint, now)) {
            Result replay = replay("REVOKE", command.idempotencyKey(), fingerprint, now, true);
            if (replay != null) return replay;
            throw new EnforcementExceptions.Conflict("The enforcement command is already in progress.");
        }
        repository.revoke(action, command, new EnforcementRepository.Actor(actor.userId(), actor.display()),
                fingerprint, now);
        String correlationId = correlationId();
        repository.event(new EnforcementRepository.Event(ulidGenerator.next(), action.id(), "REVOKED",
                lifecycle(action, now).name(), "REVOKED", now, actor.userId(), actor.display(),
                command.reasonCode(), command.reason(), correlationId, ulidGenerator.next(), command.safeMetadata()));
        repository.complete("REVOKE", command.idempotencyKey(), action.id(), now);
        return result(repository.action(action.id(), false).orElseThrow(), now, false);
    }

    @Transactional
    public Result revokeForListing(String listingId, RevokeCommand raw) {
        TrustedAdminActor actor = require(AdminPermission.LISTING_REINSTATE);
        if (raw == null || raw.enforcementActionId() == null) {
            throw new EnforcementExceptions.Validation("Enforcement action is required.");
        }
        String targetId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        EnforcementRepository.Action action = repository.action(raw.enforcementActionId(), false)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        if (!targetId.equals(action.targetId())) {
            throw new EnforcementExceptions.NotFound("Enforcement action was not found for this listing.");
        }
        return revokeTrusted(raw, actor);
    }

    @Transactional
    Result previewReplacementAfterRevocation(CreateCommand raw, String excludedActionId,
            TrustedAdminActor actor) {
        actor.require(AdminPermission.LISTING_SUSPEND);
        Instant now = clock.instant();
        EnforcementPolicy.NormalizedCreate command = policy.normalize(raw, now);
        if (!command.dryRun()) {
            throw new EnforcementExceptions.Validation("Replacement preview must be a dry run.");
        }
        EnforcementRepository.Target target = repository.lockListing(command.targetId())
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Listing target was not found."));
        if ("REMOVED_BY_ADMIN".equals(target.status())) {
            throw new EnforcementExceptions.Validation(
                    "Administratively removed listings cannot receive temporary enforcement.");
        }
        if (!"ACTIVE".equals(target.status())) {
            throw new EnforcementExceptions.Validation("Only active listings can receive temporary enforcement.");
        }
        if (target.version() != command.expectedTargetVersion()) {
            throw new EnforcementExceptions.Conflict("The listing version is stale.");
        }
        List<EnforcementRepository.Action> active = repository.active(command.targetId(), now).stream()
                .filter(action -> !action.id().equals(excludedActionId)).toList();
        boolean duplicate = active.stream().anyMatch(action -> action.actionType() == command.actionType()
                && action.scopes().equals(command.scopes()));
        if (duplicate) throw new EnforcementExceptions.Conflict("An equivalent active enforcement action exists.");
        return dryRun(command, target.version(), now, excludedActionId);
    }

    @Transactional(readOnly = true)
    public Detail detail(String listingId) {
        AuthServiceClient.PlatformAdminAuthorization admin = requireAuthorization(AdminPermission.LISTING_MODERATION_READ);
        String targetId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        EnforcementRepository.Target target = repository.listing(targetId)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Listing target was not found."));
        Instant now = clock.instant();
        List<EnforcementRepository.Action> all = repository.all(targetId);
        List<EffectiveRestriction> effective = effective(targetId, now, null);
        ActionType strongest = effective.stream().map(EffectiveRestriction::actionType)
                .max(Comparator.comparingInt(ActionType::severity)).orElse(null);
        boolean removed = "REMOVED_BY_ADMIN".equals(target.status());
        return new Detail(targetId, target.status(), target.moderationStatus(), target.version(),
                !restricted(effective, EnforcementContracts.Scope.LISTING_PUBLIC_VISIBILITY),
                !restricted(effective, EnforcementContracts.Scope.LISTING_PURCHASABILITY), strongest,
                effective,
                all.stream().filter(action -> lifecycle(action, now) == LifecycleState.ACTIVE)
                        .map(action -> view(action, now)).toList(),
                all.stream().filter(action -> lifecycle(action, now) != LifecycleState.ACTIVE)
                        .map(action -> view(action, now)).toList(),
                new AdminCapabilities(true, admin.hasPermission(AdminPermission.LISTING_SUSPEND),
                        admin.hasPermission(AdminPermission.LISTING_REINSTATE), removed,
                        removed ? "This listing is administratively removed; temporary enforcement is read-only."
                                : !"ACTIVE".equals(target.status())
                                ? "Only active listings can receive temporary enforcement." : null,
                        Set.of(EnforcementContracts.Scope.LISTING_PUBLIC_VISIBILITY,
                                EnforcementContracts.Scope.LISTING_PURCHASABILITY)));
    }

    @Transactional
    public Preview preview(CreateCommand command) {
        Result result = create(command);
        Instant now = clock.instant();
        List<ActionView> overlapping = repository.active(result.targetId(), now).stream()
                .map(action -> view(action, now)).toList();
        return preview(result, overlapping, result.effectiveRestrictions(), List.of());
    }

    @Transactional
    public Preview previewRevocation(String listingId, RevokeCommand command) {
        Result result = revokeForListing(listingId, command);
        return preview(result, List.of(), result.effectiveRestrictions(),
                result.effectiveRestrictions().isEmpty() ? List.of()
                        : List.of("Another listing enforcement remains active after this revocation."));
    }

    @Transactional(readOnly = true)
    public EvaluateBatchResponse evaluateBatch(Set<String> listingIds, Set<EnforcementContracts.Scope> scopes) {
        if (listingIds == null || listingIds.isEmpty() || listingIds.size() > 50) {
            throw new EnforcementExceptions.Validation("Between 1 and 50 listing IDs are required.");
        }
        if (scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(
                scope -> scope == null || scope.targetType() != TargetType.LISTING)) {
            throw new EnforcementExceptions.Validation("Listing capability scopes are required.");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        listingIds.stream().sorted().forEach(id -> ids.add(FixedLengthIds.requireTrimmed("Listing ID", id, 26)));
        Instant now = clock.instant();
        List<EnforcementRepository.Action> active = repository.activeForTargets(ids, now);
        Map<String, List<EnforcementRepository.Action>> byListing = new HashMap<>();
        active.forEach(action -> byListing.computeIfAbsent(action.targetId(), ignored -> new ArrayList<>()).add(action));
        Map<String, EnforcementRepository.Action> byAction = new HashMap<>();
        active.forEach(action -> byAction.put(action.id(), action));
        List<ListingCapabilityDecision> decisions = ids.stream().map(id -> {
            List<EffectiveRestriction> effective = select(byListing.getOrDefault(id, List.of()), now);
            return new ListingCapabilityDecision(id, scopes.stream().sorted(Comparator.comparing(Enum::name))
                    .map(scope -> decision(scope, effective, id, byAction)).toList());
        }).toList();
        return new EvaluateBatchResponse(now, decisions);
    }

    @Transactional(readOnly = true)
    public List<EffectiveRestriction> evaluate(String listingId, Instant at) {
        return effective(listingId, at == null ? clock.instant() : at, null);
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(String listingId) {
        require(AdminPermission.AUDIT_READ);
        return repository.timeline(listingId).stream().map(row -> new TimelineEntry(
                row.eventId(), row.occurredAt(), row.type(), "PLATFORM_ADMIN", row.actorId(), row.actorDisplay(),
                Source.HUMAN_ADMIN, TargetType.LISTING, listingId, row.actionId(), row.caseId(), row.previousState(),
                row.newState(), row.actionType(), repository.scopes(row.actionId()), row.reasonCode(), row.reason(),
                row.correlationId(), row.requestId(), row.metadata())).toList();
    }

    private Preview preview(Result result, List<ActionView> overlapping,
            List<EffectiveRestriction> effective, List<String> warnings) {
        boolean visible = !restricted(effective, EnforcementContracts.Scope.LISTING_PUBLIC_VISIBILITY);
        boolean purchasable = !restricted(effective, EnforcementContracts.Scope.LISTING_PURCHASABILITY);
        List<String> impact = new ArrayList<>();
        impact.add(visible ? "The listing remains visible in normal public discovery."
                : "The listing will be hidden from normal public discovery.");
        impact.add(purchasable ? "Listing-level purchasability remains available."
                : "Buyers cannot start a new purchase for this listing.");
        impact.add("The listing, moderation history, seller, business, and existing orders will not be changed.");
        return new Preview(result, overlapping, effective, visible, purchasable, true,
                List.copyOf(impact), warnings);
    }

    private CapabilityDecision decision(EnforcementContracts.Scope scope,
            List<EffectiveRestriction> effective, String listingId,
            Map<String, EnforcementRepository.Action> actions) {
        EffectiveRestriction restriction = effective.stream().filter(value -> value.scope() == scope)
                .findFirst().orElse(null);
        EnforcementRepository.Action action = restriction == null ? null
                : actions.get(restriction.enforcementActionId());
        return new CapabilityDecision(scope, restriction == null,
                restriction == null ? null : restriction.actionType(),
                restriction == null ? null : restriction.enforcementActionId(),
                action == null ? null : action.expiresAt(),
                restriction == null ? null : "LISTING-" + listingId.substring(listingId.length() - 6));
    }

    private boolean restricted(List<EffectiveRestriction> effective, EnforcementContracts.Scope scope) {
        return effective.stream().anyMatch(value -> value.scope() == scope);
    }

    private ActionView view(EnforcementRepository.Action action, Instant now) {
        return new ActionView(action.id(), action.actionType(), action.scopes(), lifecycle(action, now).name(),
                action.effectiveAt(), action.expiresAt(), action.version(), action.createdAt(), action.revokedAt(),
                action.reasonCode(), action.reason(), action.actorId(), action.actorDisplay(), action.caseId());
    }

    private Result replay(String type, String key, String fingerprint, Instant now, boolean lock) {
        return repository.idempotency(type, key, lock).map(value -> {
            if (!value.fingerprint().equals(fingerprint)) {
                throw new EnforcementExceptions.Conflict("The idempotency key was used with a different command.");
            }
            return value.actionId() == null ? null
                    : result(repository.action(value.actionId(), lock).orElseThrow(), now, false);
        }).orElse(null);
    }

    private Result dryRun(EnforcementPolicy.NormalizedCreate command, long version, Instant now) {
        return dryRun(command, version, now, null);
    }

    private Result dryRun(EnforcementPolicy.NormalizedCreate command, long version, Instant now,
            String excludedActionId) {
        EnforcementRepository.Action proposal = new EnforcementRepository.Action(null, command.targetId(),
                command.actionType(), command.scopes(), 0, command.effectiveAt(), command.expiresAt(),
                command.reasonCode(), command.reason(), command.caseId(), version, null, null, now, null,
                correlationId(), null, null, null, command.safeMetadata());
        List<EnforcementRepository.Action> actions = new ArrayList<>(repository.active(command.targetId(), now)
                .stream().filter(action -> excludedActionId == null || !excludedActionId.equals(action.id()))
                .toList());
        if (lifecycle(proposal, now) == LifecycleState.ACTIVE) actions.add(proposal);
        return new Result(null, TargetType.LISTING, command.targetId(), command.actionType(), command.scopes(),
                lifecycle(proposal, now), command.effectiveAt(), command.expiresAt(), 0, now, null,
                select(actions, now), proposal.correlationId(), true);
    }

    private Result result(EnforcementRepository.Action action, Instant now, boolean dryRun) {
        return new Result(action.id(), TargetType.LISTING, action.targetId(), action.actionType(), action.scopes(),
                lifecycle(action, now), action.effectiveAt(), action.expiresAt(), action.version(), action.createdAt(),
                action.revokedAt(), effective(action.targetId(), now, null), action.correlationId(), dryRun);
    }

    private List<EffectiveRestriction> effective(String targetId, Instant now, String excluded) {
        return select(repository.active(targetId, now).stream()
                .filter(action -> excluded == null || !excluded.equals(action.id())).toList(), now);
    }

    private List<EffectiveRestriction> select(List<EnforcementRepository.Action> actions, Instant at) {
        return evaluator.evaluate(actions.stream().map(action -> new EffectiveEnforcementEvaluator.Candidate(
                action.id(), action.actionType(), action.scopes(), action.effectiveAt(), action.expiresAt(),
                action.revokedAt())).toList(), at);
    }

    private LifecycleState lifecycle(EnforcementRepository.Action action, Instant at) {
        if (action.revokedAt() != null) return LifecycleState.REVOKED;
        return action.expiresAt() != null && !action.expiresAt().isAfter(at)
                ? LifecycleState.EXPIRED : LifecycleState.ACTIVE;
    }

    private TrustedAdminActor require(AdminPermission permission) {
        AuthServiceClient.PlatformAdminAuthorization admin = requireAuthorization(permission);
        return new TrustedAdminActor(admin.userId(), "Platform administrator", Set.copyOf(admin.permissions()));
    }

    private AuthServiceClient.PlatformAdminAuthorization requireAuthorization(AdminPermission permission) {
        CurrentActor current = currentActorProvider.currentActor();
        AuthServiceClient.PlatformAdminAuthorization admin = authServiceClient.requirePlatformAdmin(current.accessToken());
        if (!admin.hasPermission(permission)) {
            throw new com.msb.ecom.product_service.model.ListingAuthorizationException(
                    "Admin permission is required for this action.");
        }
        return admin;
    }

    private String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? CorrelationId.generate().value() : value;
    }

    record TrustedAdminActor(String userId, String display, Set<String> permissions) {
        void require(AdminPermission permission) {
            if (permissions == null || !permissions.contains(permission.id())) {
                throw new com.msb.ecom.product_service.model.ListingAuthorizationException(
                        "Admin permission is required for this action.");
            }
        }
    }
}
