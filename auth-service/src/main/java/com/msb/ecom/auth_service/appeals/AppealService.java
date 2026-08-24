package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.*;
import com.msb.ecom.auth_service.appeals.AppealRepository.*;
import com.msb.ecom.auth_service.appeals.ListingAppealContextClient.ListingEnforcementContext;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AdminBusinessService;
import com.msb.ecom.auth_service.service.AdminUserService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AppealService {
    private static final int MAX_EXPLANATION = 2000;
    private static final int MAX_REVIEW_REASON = 2000;
    private static final int MAX_NOTE = 4000;
    private static final Duration RESOLUTION_PREVIEW_TTL = Duration.ofMinutes(5);
    private static final Pattern REPLACEMENT_REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Set<Status> RECOMMENDED = Set.of(
            Status.UPHOLD_RECOMMENDED, Status.MODIFY_RECOMMENDED, Status.REVOKE_RECOMMENDED);
    private static final Set<Status> FINAL = Set.of(Status.UPHELD, Status.MODIFIED, Status.REVOKED);
    private static final Set<Scope> USER_SCOPES = Set.of(Scope.USER_BUYING, Scope.USER_SELLING);
    private static final Set<Scope> BUSINESS_SCOPES = Set.of(
            Scope.BUSINESS_LISTING_CREATION, Scope.BUSINESS_LISTING_PUBLICATION, Scope.BUSINESS_NEW_SALES);
    private static final Set<Scope> LISTING_SCOPES = Set.of(
            Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY);

    private final AppealRepository repository;
    private final ListingAppealContextClient listings;
    private final AuthService authService;
    private final AdminAuthorizationService authorization;
    private final AdminUserService adminUsers;
    private final AdminBusinessService adminBusinesses;
    private final EnforcementService enforcements;
    private final AppealResolutionCommandService resolutionCommands;
    private final UlidGenerator ulids;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    // Derives the appellant and target from the authoritative enforcement record; no client target identity is accepted.
    public SubmissionResult submit(String rawActionId, CreateAppealRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        String actionId = id("Enforcement action ID", rawActionId);
        NormalizedCreate normalized = normalizeCreate(request);
        ResolvedEnforcement enforcement = resolve(actionId);
        ensureActive(enforcement);
        Set<String> businessIds = repository.authorizedBusinessIds(actor.getId());
        AppellantType appellantType = eligibleAppellant(actor.getId(), businessIds, enforcement);
        Instant now = clock.instant();
        String appealId = ulids.next();
        AppealRow row = new AppealRow(appealId, actionId, enforcement.targetType(), enforcement.targetId(),
                enforcement.safeLabel(), appellantType, actor.getId(), Status.SUBMITTED, normalized.reasonCode(),
                normalized.explanation(), List.<String>of(), now, null, null, null, null, null, null, null,
                null, null, null, null, enforcement.caseId(), enforcement.version(),
                correlation(correlationId), 0, now, now, null, null, null, null,
                null, null, null, null, null);
        if (!repository.insert(row)) {
            throw AppealException.conflict("APPEAL_ALREADY_EXISTS",
                    "An appeal already exists for this enforcement action.");
        }
        repository.insertEvent(event(row.id(), "APPEAL_SUBMITTED", "MARKETPLACE_USER", actor.getId(),
                safeName(actor), "MARKETPLACE", null, Status.SUBMITTED.name(), normalized.reasonCode().name(),
                normalized.explanation(), row.correlationId(), Map.of("enforcementActionId", actionId)));
        return new SubmissionResult(appealId, Status.SUBMITTED, now, supportReference(appealId));
    }

    @Transactional(readOnly = true)
    public List<EnforcementNotice> myEnforcementNotices() {
        User actor = authService.ensureUserEntity();
        Instant now = clock.instant();
        Set<String> businessIds = repository.authorizedBusinessIds(actor.getId());
        List<ResolvedEnforcement> all = new ArrayList<>();
        repository.localActiveForActor(actor.getId(), businessIds, now).forEach(value -> all.add(from(value)));
        listings.activeForActor(actor.getId(), businessIds).forEach(value -> all.add(from(value)));
        return all.stream().sorted(Comparator.comparing(ResolvedEnforcement::createdAt).reversed())
                .map(this::notice).toList();
    }

    @Transactional(readOnly = true)
    public List<MyAppeal> mine() {
        User actor = authService.ensureUserEntity();
        return repository.mine(actor.getId()).stream().map(row -> {
            ResolvedEnforcement enforcement = resolve(row.enforcementActionId());
            return new MyAppeal(row.id(), row.enforcementActionId(), row.targetType(), row.targetId(),
                    row.safeTargetLabel(), enforcement.actionType(), row.status(), row.submittedAt(),
                    row.updatedAt(), supportReference(row.id()), row.resolvedAt(),
                    row.resolutionSummary(), currentEffectiveState(enforcement));
        }).toList();
    }

    @Transactional(readOnly = true)
    public Page search(String query, TargetType targetType, Status status, ReasonCode reasonCode,
                       Assignment assignment, Instant submittedFrom, Instant submittedTo,
                       int page, int size, String sort) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_READ);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String safeSort = normalizeSort(sort);
        SearchResult result = repository.search(new Search(normalizeSearch(query), targetType, status, reasonCode,
                assignment == null ? Assignment.UNASSIGNED : assignment, submittedFrom, submittedTo,
                safePage, safeSize, safeSort, actor.getId()));
        List<Summary> items = result.rows().stream().map(row -> new Summary(row.id(), row.enforcementActionId(),
                row.targetType(), row.targetId(), row.safeTargetLabel(), resolve(row.enforcementActionId()).actionType(),
                row.reasonCode(), row.status(), row.assignedAdminId(), row.submittedAt(), row.updatedAt(), row.version()))
                .toList();
        return new Page(items, safePage, safeSize, result.total(),
                result.total() == 0 ? 0 : (int) Math.ceil((double) result.total() / safeSize), safeSort);
    }

    @Transactional(readOnly = true)
    public Detail detail(String rawAppealId) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorization.accessFor(actor);
        if (!access.has(AdminPermission.APPEAL_READ)) {
            throw AppealException.forbidden("APPEAL_READ_FORBIDDEN", "Appeal review permission is required.");
        }
        AppealRow row = repository.byId(id("Appeal ID", rawAppealId), false).orElseThrow(AppealException::notFound);
        ResolvedEnforcement enforcement = resolve(row.enforcementActionId());
        return detail(row, enforcement, actor, access);
    }

    @Transactional
    public Detail claim(String rawAppealId, VersionRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_ASSIGN);
        String appealId = id("Appeal ID", rawAppealId);
        long version = version(request == null ? null : request.expectedVersion());
        AppealRow before = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        if (!repository.claim(appealId, version, actor.getId(), clock.instant())) conflict(before);
        repository.insertEvent(adminEvent(appealId, "APPEAL_CLAIMED", actor, before.status(), before.status(),
                null, null, correlationId));
        return detail(appealId);
    }

    @Transactional
    public Detail release(String rawAppealId, VersionRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_ASSIGN);
        String appealId = id("Appeal ID", rawAppealId);
        long version = version(request == null ? null : request.expectedVersion());
        AppealRow before = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        ensureOwned(before, actor);
        if (!repository.release(appealId, version, actor.getId(), clock.instant())) conflict(before);
        repository.insertEvent(adminEvent(appealId, "APPEAL_RELEASED", actor, before.status(), before.status(),
                null, null, correlationId));
        return detail(appealId);
    }

    @Transactional
    public Detail startReview(String rawAppealId, VersionRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_REVIEW);
        String appealId = id("Appeal ID", rawAppealId);
        long version = version(request == null ? null : request.expectedVersion());
        AppealRow before = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        ensureOwned(before, actor);
        if (!repository.start(appealId, version, actor.getId(), clock.instant())) conflict(before);
        repository.insertEvent(adminEvent(appealId, "APPEAL_REVIEW_STARTED", actor, before.status(),
                Status.UNDER_REVIEW, null, null, correlationId));
        return detail(appealId);
    }

    @Transactional
    public Detail addNote(String rawAppealId, NoteRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_REVIEW);
        String appealId = id("Appeal ID", rawAppealId);
        if (request == null) throw AppealException.invalid("APPEAL_NOTE_INVALID", "Review note is required.");
        String body = text(request.body(), "Review note", MAX_NOTE, true);
        String key = bounded(request.idempotencyKey(), "Idempotency key", 128, true);
        Optional<NoteRow> existing = repository.noteByRetry(appealId, actor.getId(), key);
        if (existing.isPresent()) {
            if (!existing.get().body().equals(body)) {
                throw AppealException.conflict("APPEAL_NOTE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was already used for a different review note.");
            }
            return detail(appealId);
        }
        AppealRow before = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        ensureOwned(before, actor);
        if (!repository.touchForNote(appealId, version(request.expectedVersion()), actor.getId(), clock.instant())) conflict(before);
        Instant now = clock.instant();
        if (!repository.insertNote(new NoteRow(ulids.next(), appealId, body, actor.getId(), safeName(actor),
                correlation(correlationId), key, now))) {
            Optional<NoteRow> replay = repository.noteByRetry(appealId, actor.getId(), key);
            if (replay.isPresent() && replay.get().body().equals(body)) return detail(appealId);
            throw AppealException.conflict("APPEAL_NOTE_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different review note.");
        }
        repository.insertEvent(adminEvent(appealId, "APPEAL_NOTE_ADDED", actor, before.status(), before.status(),
                null, "Internal review note added.", correlationId));
        return detail(appealId);
    }

    @Transactional
    // Persists a recommendation only. This method intentionally has no dependency on any enforcement mutation service.
    public Detail review(String rawAppealId, ReviewRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        require(actor, AdminPermission.APPEAL_REVIEW);
        if (request == null || request.outcome() == null) {
            throw AppealException.invalid("APPEAL_REVIEW_INVALID", "Review outcome is required.");
        }
        String appealId = id("Appeal ID", rawAppealId);
        AppealRow before = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        ensureOwned(before, actor);
        if (before.status() != Status.UNDER_REVIEW) {
            throw AppealException.conflict("APPEAL_NOT_REVIEWABLE", "Appeal is not under review.");
        }
        ResolvedEnforcement enforcement = resolve(before.enforcementActionId());
        if ("REVOKED".equals(enforcement.lifecycle())) {
            throw AppealException.conflict("ENFORCEMENT_CHANGED", "The original enforcement has changed. Reload the appeal.");
        }
        String reasonCode = bounded(request.reasonCode(), "Review reason code", 64, true);
        String reason = text(request.reason(), "Review reason", MAX_REVIEW_REASON, true);
        ReplacementProposal replacement = normalizeReplacement(request.outcome(), request.replacementProposal(), enforcement);
        if (!repository.recommend(appealId, version(request.expectedVersion()), actor.getId(), request.outcome(),
                reasonCode, reason, replacement, clock.instant())) conflict(before);
        String type = switch (request.outcome()) {
            case UPHOLD_RECOMMENDED -> "APPEAL_UPHOLD_RECOMMENDED";
            case MODIFY_RECOMMENDED -> "APPEAL_MODIFY_RECOMMENDED";
            case REVOKE_RECOMMENDED -> "APPEAL_REVOKE_RECOMMENDED";
        };
        repository.insertEvent(adminEvent(appealId, type, actor, before.status(),
                Status.valueOf(request.outcome().name()), reasonCode, reason, correlationId));
        return detail(appealId);
    }

    @Transactional
    // Produces a short-lived confirmation bound to every authoritative state used by final execution.
    public ResolutionPreview previewResolution(String rawAppealId, ResolutionPreviewRequest request,
                                               String correlationId) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorization.accessFor(actor);
        requireResolution(access, AdminPermission.APPEAL_RESOLVE,
                "Appeal-resolution permission is required.");
        ResolutionVersions versions = resolutionVersions(request);
        String appealId = id("Appeal ID", rawAppealId);
        AppealRow appeal = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        ensureRecommended(appeal);
        if (appeal.version() != versions.appealVersion()) {
            throw AppealException.conflict("APPEAL_VERSION_CONFLICT",
                    "Appeal state changed. Reload before continuing.");
        }
        Instant previewedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        EnforcementService.StatePin localPin = appeal.targetType() == TargetType.LISTING ? null
                : enforcements.lockState(appeal.targetType(), appeal.targetId(), previewedAt);
        ResolvedEnforcement original = resolve(appeal.enforcementActionId());
        validateResolutionState(appeal, original, versions);
        validateLocalPin(localPin, versions);
        requireResolutionPermissions(access, appeal);

        FinalOutcome outcome = finalOutcome(appeal.status());
        PreviewComputation computation;
        try {
            computation = original.productOwned()
                    ? previewListing(appeal, original, outcome, versions, actor, access, previewedAt)
                    : previewLocal(appeal, original, outcome, versions, localPin, previewedAt);
        } catch (EnforcementExceptions.Validation exception) {
            throw AppealException.invalid("APPEAL_RESOLUTION_INVALID", exception.getMessage());
        } catch (EnforcementExceptions.NotFound exception) {
            throw AppealException.enforcementNotFound();
        } catch (EnforcementExceptions.Conflict exception) {
            throw AppealException.conflict("ENFORCEMENT_VERSION_CONFLICT", exception.getMessage());
        }

        Instant now = previewedAt;
        Instant expiresAt = previewExpiresAt(appeal, now);
        String token = ulids.next();
        String fingerprint = previewFingerprint(appeal, original, versions,
                computation.ownerConfirmationToken());
        if (!repository.insertPreview(new ResolutionPreviewRow(token, appealId, actor.getId(), fingerprint,
                computation.ownerConfirmationToken(), expiresAt, now))) {
            throw AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_INVALID",
                    "The resolution preview could not be reserved. Preview again.");
        }
        return new ResolutionPreview(outcome, appeal.version(), original.version(), versions.targetVersion(),
                original.id(), replacement(appeal), computation.predictedState(), computation.restrictions(),
                computation.warnings(), computation.impactSummary(), token, expiresAt);
    }

    @Transactional
    // Executes Auth-owned USER/BUSINESS changes atomically; Product-owned LISTING execution is idempotent and recoverable.
    public Detail resolveTransaction(String rawAppealId, ResolutionRequest request, String correlationId) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorization.accessFor(actor);
        requireResolution(access, AdminPermission.APPEAL_RESOLVE,
                "Appeal-resolution permission is required.");
        NormalizedResolution command = normalizeResolution(request);
        String appealId = id("Appeal ID", rawAppealId);
        AppealRow appeal = repository.byId(appealId, true).orElseThrow(AppealException::notFound);
        String requestHash = resolutionRequestHash(appeal, command, actor.getId());

        if (FINAL.contains(appeal.status())) {
            if (command.idempotencyKey().equals(appeal.resolutionIdempotencyKey())) {
                if (requestHash.equals(appeal.resolutionRequestHash())) {
                    return detail(appeal, resolve(appeal.enforcementActionId()), actor, access);
                }
                throw AppealException.conflict("APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was already used with a different resolution request.");
            }
            throw AppealException.conflict("APPEAL_READ_ONLY", "The final appeal resolution is immutable.");
        }
        AppealRow keyOwner = repository.byResolutionKey(command.idempotencyKey(), false).orElse(null);
        if (keyOwner != null && (!keyOwner.id().equals(appealId)
                || !requestHash.equals(keyOwner.resolutionRequestHash()))) {
            throw AppealException.conflict("APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different appeal resolution.");
        }
        ensureRecommended(appeal);
        if (appeal.version() != command.versions().appealVersion()) {
            throw AppealException.conflict("APPEAL_VERSION_CONFLICT",
                    "Appeal state changed. Reload before continuing.");
        }
        requireResolutionPermissions(access, appeal);
        AppealResolutionCommandService.Reservation reservation = resolutionCommands
                .find(command.idempotencyKey()).orElse(null);
        if (reservation != null) {
            resolutionCommands.requireMatch(reservation, appealId, requestHash, actor.getId());
        }
        Instant executionAt = clock.instant();
        EnforcementService.StatePin localPin = appeal.targetType() == TargetType.LISTING ? null
                : enforcements.lockState(appeal.targetType(), appeal.targetId(), executionAt);
        ResolvedEnforcement original = resolve(appeal.enforcementActionId());

        ResolutionPreviewRow preview = repository.preview(command.previewToken(), appealId, actor.getId())
                .orElseThrow(() -> AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_INVALID",
                        "Preview the resolution again before confirming."));
        boolean recoveryOnly = false;
        try {
            validateResolutionState(appeal, original, command.versions());
            validateLocalPin(localPin, command.versions());
            ensureReplacementExecutable(appeal, executionAt);
            if (!preview.expiresAt().isAfter(executionAt)) {
                throw AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_EXPIRED",
                        "The resolution preview expired. Preview again.");
            }
            String ownerToken = original.productOwned()
                    ? preview.ownerConfirmationToken() : localPin.confirmationToken();
            String currentFingerprint = previewFingerprint(appeal, original, command.versions(), ownerToken);
            if (!preview.requestFingerprint().equals(currentFingerprint)) {
                throw AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_INVALID",
                        "Relevant appeal or enforcement state changed. Preview again.");
            }
        } catch (AppealException exception) {
            if (reservation == null || !original.productOwned()) throw exception;
            recoveryOnly = true;
        }
        if (reservation == null) {
            reservation = resolutionCommands.reserve(command.idempotencyKey(), appealId,
                    requestHash, actor.getId(), executionAt);
        }

        try {
            FinalOutcome outcome = finalOutcome(appeal.status());
            ExecutionComputation execution;
            try {
                execution = original.productOwned()
                        ? executeListing(appeal, original, outcome, command, actor, access,
                                preview.ownerConfirmationToken(), preview.createdAt(), recoveryOnly)
                        : executeLocal(appeal, original, outcome, command, preview.createdAt());
            } catch (EnforcementExceptions.Validation exception) {
                throw AppealException.invalid("APPEAL_RESOLUTION_INVALID", exception.getMessage());
            } catch (EnforcementExceptions.NotFound exception) {
                throw AppealException.enforcementNotFound();
            } catch (EnforcementExceptions.Conflict exception) {
                throw AppealException.conflict("ENFORCEMENT_VERSION_CONFLICT", exception.getMessage());
            }

            Instant now = clock.instant();
            String summary = safeResolutionSummary(outcome, execution.currentEffectiveState());
            if (!repository.finalizeResolution(appealId, appeal.version(), appeal.status(), outcome,
                    actor.getId(), safeName(actor), summary, command.idempotencyKey(), requestHash,
                    execution.replacementEnforcementActionId(), execution.originalEnforcementVersion(),
                    command.versions().targetVersion(), now)) {
                AppealRow current = repository.byResolutionKey(command.idempotencyKey(), false).orElse(null);
                if (current != null && current.id().equals(appealId)
                        && requestHash.equals(current.resolutionRequestHash())) {
                    return detail(current, resolvedOriginalSnapshot(original, outcome, execution), actor, access);
                }
                throw AppealException.conflict("APPEAL_VERSION_CONFLICT",
                        "Another administrator resolved the appeal first.");
            }
            String eventType = switch (outcome) {
                case UPHELD -> "APPEAL_UPHELD";
                case MODIFIED -> "APPEAL_ENFORCEMENT_MODIFIED";
                case REVOKED -> "APPEAL_ENFORCEMENT_REVOKED";
            };
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("originalEnforcementActionId", original.id());
            if (execution.replacementEnforcementActionId() != null) {
                metadata.put("replacementEnforcementActionId", execution.replacementEnforcementActionId());
            }
            repository.insertEvent(event(appealId, eventType, "PLATFORM_ADMIN", actor.getId(), safeName(actor),
                    "HUMAN_ADMIN", appeal.status().name(), outcome.name(), appeal.reviewReasonCode(), summary,
                    correlation(correlationId), Map.copyOf(metadata)));
            AppealRow finalized = repository.byId(appealId, false).orElseThrow(AppealException::notFound);
            return detail(finalized, resolvedOriginalSnapshot(original, outcome, execution), actor, access);
        } catch (RuntimeException exception) {
            throw new AppealResolutionAttemptException(exception);
        }
    }

    private Detail detail(AppealRow row, ResolvedEnforcement enforcement, User actor,
                          AdminAuthorizationService.AdminAccessSnapshot access) {
        AdminSummary assigned = repository.admin(row.assignedAdminId())
                .map(value -> new AdminSummary(value.id(), value.displayName())).orElse(null);
        String appellantName = repository.userLabel(row.appellantUserId());
        ReplacementProposal replacement = replacement(row);
        EnforcementSummary summary = enforcementSummary(enforcement);
        EnforcementSummary replacementSummary = row.replacementEnforcementActionId() == null ? null
                : enforcementSummary(resolve(row.replacementEnforcementActionId()));
        OriginalCaseSummary caseSummary = repository.caseSummary(enforcement.caseId()).map(value ->
                new OriginalCaseSummary(value.id(), value.title(), value.status(), value.severity(),
                        value.closedAt(), value.conclusionCode())).orElse(null);
        List<LinkedReport> reports = repository.caseReports(enforcement.caseId()).stream().map(value ->
                new LinkedReport(value.id(), value.reasonCode(), value.severity(), value.status(),
                        value.label(), value.createdAt())).toList();
        JsonNode current = enforcement.currentTarget() == null
                ? repository.currentTarget(row.targetType(), row.targetId()) : enforcement.currentTarget();
        List<TimelineEntry> enforcementTimeline = enforcement.productOwned()
                ? List.of(new TimelineEntry(enforcement.id(), enforcement.createdAt(), "CREATED", "PLATFORM_ADMIN",
                    null, "Platform administrator", "HUMAN_ADMIN", null, enforcement.lifecycle(),
                    enforcement.reasonCode(), enforcement.reason(), null, null, Map.of()))
                : timeline(repository.localEnforcementEvents(enforcement.id()));
        return new Detail(row.id(), row.enforcementActionId(), row.targetType(), row.targetId(), row.safeTargetLabel(),
                new AppellantSummary(row.appellantType(), row.appellantUserId(), appellantName), row.reasonCode(),
                row.explanation(), row.evidenceReferences(), row.status(), assigned, row.submittedAt(),
                row.reviewStartedAt(), row.reviewedAt(), row.reviewOutcome(), row.reviewReasonCode(), row.reviewReason(),
                replacement, summary, currentEffectiveState(enforcement), row.resolvedAt(),
                FINAL.contains(row.status()) ? FinalOutcome.valueOf(row.status().name()) : null,
                row.resolutionSummary(), replacementSummary, caseSummary, reports,
                repository.originalSnapshot(enforcement.caseId()), current, enforcementTimeline,
                timeline(repository.caseEvents(enforcement.caseId())), timeline(repository.events(row.id())),
                repository.notes(row.id()).stream().map(note -> new ReviewNote(note.id(), note.body(), note.adminId(),
                        note.adminName(), note.createdAt())).toList(), row.createdAt(), row.updatedAt(), row.version(),
                capabilities(row, actor, access));
    }

    private Capabilities capabilities(AppealRow row, User actor,
                                      AdminAuthorizationService.AdminAccessSnapshot access) {
        boolean finalRecommendation = RECOMMENDED.contains(row.status());
        boolean finalResolution = FINAL.contains(row.status());
        boolean mine = actor.getId().equals(row.assignedAdminId());
        boolean other = row.assignedAdminId() != null && !mine;
        boolean assign = access.has(AdminPermission.APPEAL_ASSIGN);
        boolean review = access.has(AdminPermission.APPEAL_REVIEW);
        boolean mutable = !finalRecommendation && !finalResolution;
        boolean canResolveUphold = row.status() == Status.UPHOLD_RECOMMENDED
                && hasResolutionPermissions(access, row);
        boolean canResolveModify = row.status() == Status.MODIFY_RECOMMENDED
                && hasResolutionPermissions(access, row);
        boolean canResolveRevoke = row.status() == Status.REVOKE_RECOMMENDED
                && hasResolutionPermissions(access, row);
        boolean canResolve = canResolveUphold || canResolveModify || canResolveRevoke;
        String reason = finalResolution ? "The final appeal resolution is immutable."
                : finalRecommendation ? canResolve ? null
                        : "A recommendation has been recorded; resolution authority is required."
                : other ? "This appeal is assigned to another reviewer."
                : !review ? "You have read-only access to appeals."
                : row.assignedAdminId() == null ? "Claim this appeal before reviewing it." : null;
        boolean canReview = mutable && mine && review && row.status() == Status.UNDER_REVIEW;
        return new Capabilities(true, mutable && row.assignedAdminId() == null && assign,
                mutable && mine && assign, mutable && mine && review && row.status() == Status.SUBMITTED,
                canReview, canReview, canReview, canReview, canReview, mine, other, finalRecommendation,
                canResolveUphold, canResolveModify, canResolveRevoke, finalResolution,
                reason != null, reason);
    }

    private EnforcementNotice notice(ResolvedEnforcement enforcement) {
        AppealRow appeal = repository.byAction(enforcement.id()).orElse(null);
        boolean active = "ACTIVE".equals(enforcement.lifecycle());
        return new EnforcementNotice(enforcement.id(), enforcement.targetType(), enforcement.targetId(),
                enforcement.safeLabel(), enforcement.actionType(), enforcement.scopes(), enforcement.effectiveAt(),
                enforcement.expiresAt(), enforcementSupportReference(enforcement.id()), active && appeal == null,
                !active ? "Only active enforcement can be appealed." : appeal != null ? "An appeal already exists." : null,
                appeal == null ? null : appeal.id(), appeal == null ? null : appeal.status());
    }

    private ResolvedEnforcement resolve(String actionId) {
        return repository.localEnforcement(actionId).map(this::from)
                .orElseGet(() -> listings.byActionId(actionId).map(this::from)
                        .orElseThrow(AppealException::enforcementNotFound));
    }

    private ResolvedEnforcement from(EnforcementRow value) {
        return new ResolvedEnforcement(value.id(), value.targetType(), value.targetId(), value.safeLabel(),
                value.actionType(), value.scopes(), value.lifecycle(), value.effectiveAt(), value.expiresAt(),
                value.version(), value.createdAt(), value.reasonCode(), value.reason(), value.caseId(), null,
                null, null, null, false);
    }

    private ResolvedEnforcement from(ListingEnforcementContext value) {
        Set<Scope> scopes = value.scopes().stream().map(this::scope).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        JsonNode current = mapper.valueToTree(Map.of("id", value.listingId(), "label", safe(value.safeListingLabel(), "Marketplace listing"),
                "status", safe(value.listingStatus(), "UNAVAILABLE"), "version", value.listingVersion()));
        return new ResolvedEnforcement(value.enforcementActionId(), TargetType.LISTING, value.listingId(),
                safe(value.safeListingLabel(), "Marketplace listing"), ActionType.valueOf(value.actionType()), scopes,
                value.lifecycleState(), value.effectiveAt(), value.expiresAt(), value.enforcementVersion(),
                value.createdAt(), value.reasonCode(), value.reason(), value.caseId(), value.individualSellerUserId(),
                value.businessId(), current, value.currentEffectiveEnforcementState(), true);
    }

    private AppellantType eligibleAppellant(String userId, Set<String> businessIds, ResolvedEnforcement enforcement) {
        return switch (enforcement.targetType()) {
            case USER -> {
                if (!userId.equals(enforcement.targetId())) denyEligibility();
                yield AppellantType.USER;
            }
            case BUSINESS -> {
                if (!businessIds.contains(enforcement.targetId())) denyEligibility();
                yield AppellantType.BUSINESS_REPRESENTATIVE;
            }
            case LISTING -> {
                boolean individual = userId.equals(enforcement.individualOwnerId());
                boolean business = enforcement.businessOwnerId() != null && businessIds.contains(enforcement.businessOwnerId());
                if (!individual && !business) denyEligibility();
                yield AppellantType.LISTING_OWNER;
            }
        };
    }

    private void denyEligibility() {
        throw AppealException.forbidden("APPEAL_NOT_ELIGIBLE",
                "You are not eligible to appeal this enforcement action.");
    }

    private PreviewComputation previewLocal(AppealRow appeal, ResolvedEnforcement original,
                                            FinalOutcome outcome, ResolutionVersions versions,
                                            EnforcementService.StatePin pin, Instant previewedAt) {
        if (outcome == FinalOutcome.UPHELD) {
            List<EffectiveRestriction> restrictions = pin.effectiveRestrictions();
            return new PreviewComputation(effectiveState(restrictions), restrictions, List.of(),
                    List.of("The original enforcement will remain unchanged."), pin.confirmationToken());
        }
        LocalPreview revoked = revokePreview(appeal, original, versions.enforcementVersion());
        if (outcome == FinalOutcome.REVOKED) {
            return new PreviewComputation(effectiveState(revoked.restrictions()), revoked.restrictions(),
                    revoked.warnings(), revoked.impactSummary(), pin.confirmationToken());
        }
        LocalPreview replacement = replacementPreview(appeal, original, versions.targetVersion(), previewedAt);
        List<String> warnings = new ArrayList<>(revoked.warnings());
        warnings.addAll(replacement.warnings());
        List<String> impact = new ArrayList<>(revoked.impactSummary());
        impact.addAll(replacement.impactSummary());
        return new PreviewComputation(effectiveState(replacement.restrictions()), replacement.restrictions(),
                List.copyOf(warnings), List.copyOf(impact), pin.confirmationToken());
    }

    private PreviewComputation previewListing(AppealRow appeal, ResolvedEnforcement original,
                                               FinalOutcome outcome, ResolutionVersions versions,
                                               User actor, AdminAuthorizationService.AdminAccessSnapshot access,
                                               Instant previewedAt) {
        ListingAppealContextClient.ListingResolutionResult result = listings.previewResolution(appeal.id(),
                listingRequest(appeal, original, outcome, versions, actor, access, null, false, null,
                        previewedAt, false));
        if (result == null || result.confirmationToken() == null || result.confirmationToken().isBlank()) {
            throw AppealException.unavailable("Listing appeal resolution preview is temporarily unavailable.");
        }
        return new PreviewComputation(result.currentEffectiveEnforcementState(),
                listingRestrictions(result.effectiveRestrictions()), safeList(result.warnings()),
                safeList(result.impactSummary()), result.confirmationToken());
    }

    private ExecutionComputation executeLocal(AppealRow appeal, ResolvedEnforcement original,
                                              FinalOutcome outcome, NormalizedResolution command,
                                              Instant replacementEffectiveAt) {
        if (outcome == FinalOutcome.UPHELD) {
            return new ExecutionComputation(original.version(), null, currentEffectiveState(original));
        }
        Result revoked = revokeConfirmed(appeal, original, command.versions().enforcementVersion(),
                subKey("revoke", appeal.id(), command.idempotencyKey()));
        if (outcome == FinalOutcome.REVOKED) {
            return new ExecutionComputation(revoked.version(), null,
                    effectiveState(revoked.effectiveRestrictions()));
        }
        Result replacement = createReplacement(appeal, original, command.versions().targetVersion(),
                subKey("replace", appeal.id(), command.idempotencyKey()), replacementEffectiveAt);
        return new ExecutionComputation(revoked.version(), replacement.enforcementActionId(),
                effectiveState(replacement.effectiveRestrictions()));
    }

    private ExecutionComputation executeListing(AppealRow appeal, ResolvedEnforcement original,
                                                FinalOutcome outcome, NormalizedResolution command,
                                                 User actor, AdminAuthorizationService.AdminAccessSnapshot access,
                                                 String ownerConfirmationToken, Instant replacementEffectiveAt,
                                                 boolean recoveryOnly) {
        ListingAppealContextClient.ListingResolutionResult result = listings.executeResolution(appeal.id(),
                listingRequest(appeal, original, outcome, command.versions(), actor, access,
                        command.idempotencyKey(), true, ownerConfirmationToken,
                        replacementEffectiveAt, recoveryOnly));
        if (result == null) {
            throw AppealException.unavailable("Listing appeal resolution is temporarily unavailable.");
        }
        return new ExecutionComputation(result.originalEnforcementVersion(),
                result.replacementEnforcementActionId(), result.currentEffectiveEnforcementState());
    }

    private ListingAppealContextClient.ListingResolutionRequest listingRequest(
            AppealRow appeal, ResolvedEnforcement original, FinalOutcome outcome, ResolutionVersions versions,
            User actor, AdminAuthorizationService.AdminAccessSnapshot access, String idempotencyKey,
            boolean confirmed, String confirmationToken, Instant replacementEffectiveAt,
            boolean recoveryOnly) {
        ReplacementProposal proposal = replacement(appeal);
        ListingAppealContextClient.ListingReplacement replacement = proposal == null ? null
                : new ListingAppealContextClient.ListingReplacement(proposal.actionType().name(),
                proposal.scopes().stream().map(Enum::name).sorted().toList(), replacementEffectiveAt,
                proposal.expiresAt(), proposal.reasonCode(), proposal.reason());
        return new ListingAppealContextClient.ListingResolutionRequest(outcome.name(), original.id(),
                versions.enforcementVersion(), versions.targetVersion(), resolutionReasonCode(outcome),
                resolutionReason(appeal, outcome), idempotencyKey, replacement,
                new ListingAppealContextClient.ListingExecutor(actor.getId(), safeName(actor), access.permissions()),
                Map.of("origin", "APPEAL_RESOLUTION"), confirmed, confirmationToken, recoveryOnly);
    }

    private LocalPreview revokePreview(AppealRow appeal, ResolvedEnforcement original, long enforcementVersion) {
        if (original.targetType() == TargetType.USER) {
            com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview preview = adminUsers.revokePreview(
                    original.targetId(), original.id(), new com.msb.ecom.auth_service.dto.AdminUserContracts.RevokeEnforcementRequest(
                            enforcementVersion, resolutionReasonCode(FinalOutcome.REVOKED),
                            resolutionReason(appeal, FinalOutcome.REVOKED), null,
                            Map.of("origin", "APPEAL_RESOLUTION")));
            return new LocalPreview(preview.effectiveRestrictionsAfter(), preview.warnings(), List.of());
        }
        com.msb.ecom.auth_service.dto.AdminBusinessContracts.EnforcementPreview preview = adminBusinesses.revokePreview(
                original.targetId(), original.id(), new com.msb.ecom.auth_service.dto.AdminBusinessContracts.RevokeEnforcementRequest(
                        enforcementVersion, resolutionReasonCode(FinalOutcome.REVOKED),
                        resolutionReason(appeal, FinalOutcome.REVOKED), null,
                        Map.of("origin", "APPEAL_RESOLUTION")));
        return new LocalPreview(preview.effectiveRestrictionsAfter(), preview.warnings(), preview.impactSummary());
    }

    private LocalPreview replacementPreview(AppealRow appeal, ResolvedEnforcement original, long targetVersion,
                                            Instant effectiveAt) {
        ReplacementProposal proposal = requiredReplacement(appeal);
        if (original.targetType() == TargetType.USER) {
            com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview preview =
                    adminUsers.createAppealReplacementPreview(original.targetId(), original.id(),
                            new com.msb.ecom.auth_service.dto.AdminUserContracts.CreateEnforcementRequest(
                                    proposal.actionType(), proposal.scopes(), proposal.reasonCode(), proposal.reason(),
                                    effectiveAt, proposal.expiresAt(), targetVersion, null,
                                    Map.of("origin", "APPEAL_RESOLUTION")), appeal.originalCaseId());
            return new LocalPreview(preview.effectiveRestrictionsAfter(), preview.warnings(), List.of());
        }
        com.msb.ecom.auth_service.dto.AdminBusinessContracts.EnforcementPreview preview =
                adminBusinesses.createAppealReplacementPreview(original.targetId(), original.id(),
                        new com.msb.ecom.auth_service.dto.AdminBusinessContracts.CreateEnforcementRequest(
                                proposal.actionType(), proposal.scopes(), proposal.reasonCode(), proposal.reason(),
                                effectiveAt, proposal.expiresAt(), targetVersion, null,
                                Map.of("origin", "APPEAL_RESOLUTION")), appeal.originalCaseId());
        return new LocalPreview(preview.effectiveRestrictionsAfter(), preview.warnings(), preview.impactSummary());
    }

    private Result revokeConfirmed(AppealRow appeal, ResolvedEnforcement original, long enforcementVersion,
                                   String idempotencyKey) {
        if (original.targetType() == TargetType.USER) {
            return adminUsers.revokeConfirmed(original.targetId(), original.id(),
                    new com.msb.ecom.auth_service.dto.AdminUserContracts.RevokeEnforcementRequest(
                            enforcementVersion, resolutionReasonCode(FinalOutcome.REVOKED),
                            resolutionReason(appeal, FinalOutcome.REVOKED), idempotencyKey,
                            Map.of("origin", "APPEAL_RESOLUTION")));
        }
        return adminBusinesses.revokeConfirmed(original.targetId(), original.id(),
                new com.msb.ecom.auth_service.dto.AdminBusinessContracts.RevokeEnforcementRequest(
                        enforcementVersion, resolutionReasonCode(FinalOutcome.REVOKED),
                        resolutionReason(appeal, FinalOutcome.REVOKED), idempotencyKey,
                        Map.of("origin", "APPEAL_RESOLUTION")));
    }

    private Result createReplacement(AppealRow appeal, ResolvedEnforcement original, long targetVersion,
                                     String idempotencyKey, Instant effectiveAt) {
        ReplacementProposal proposal = requiredReplacement(appeal);
        if (original.targetType() == TargetType.USER) {
            return adminUsers.createCaseConfirmed(original.targetId(),
                    new com.msb.ecom.auth_service.dto.AdminUserContracts.CreateEnforcementRequest(
                            proposal.actionType(), proposal.scopes(), proposal.reasonCode(), proposal.reason(),
                            effectiveAt, proposal.expiresAt(), targetVersion, idempotencyKey,
                            Map.of("origin", "APPEAL_RESOLUTION")), appeal.originalCaseId());
        }
        return adminBusinesses.createCaseConfirmed(original.targetId(),
                new com.msb.ecom.auth_service.dto.AdminBusinessContracts.CreateEnforcementRequest(
                        proposal.actionType(), proposal.scopes(), proposal.reasonCode(), proposal.reason(),
                        effectiveAt, proposal.expiresAt(), targetVersion, idempotencyKey,
                        Map.of("origin", "APPEAL_RESOLUTION")), appeal.originalCaseId());
    }

    private ResolutionVersions resolutionVersions(ResolutionPreviewRequest request) {
        if (request == null) {
            throw AppealException.invalid("APPEAL_RESOLUTION_INVALID", "Resolution versions are required.");
        }
        return new ResolutionVersions(requiredVersion(request.expectedAppealVersion(), "Appeal"),
                requiredVersion(request.expectedEnforcementVersion(), "Enforcement"),
                requiredVersion(request.expectedTargetVersion(), "Target"));
    }

    private NormalizedResolution normalizeResolution(ResolutionRequest request) {
        if (request == null) {
            throw AppealException.invalid("APPEAL_RESOLUTION_INVALID", "Resolution confirmation is required.");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw AppealException.invalid("APPEAL_RESOLUTION_CONFIRMATION_REQUIRED",
                    "Explicit resolution confirmation is required.");
        }
        return new NormalizedResolution(new ResolutionVersions(
                requiredVersion(request.expectedAppealVersion(), "Appeal"),
                requiredVersion(request.expectedEnforcementVersion(), "Enforcement"),
                requiredVersion(request.expectedTargetVersion(), "Target")),
                bounded(request.previewToken(), "Resolution preview token", 26, true),
                bounded(request.idempotencyKey(), "Idempotency key", 128, true));
    }

    private void validateResolutionState(AppealRow appeal, ResolvedEnforcement original,
                                         ResolutionVersions versions) {
        if (!"ACTIVE".equals(original.lifecycle())) {
            throw AppealException.conflict("ENFORCEMENT_VERSION_CONFLICT",
                    "The original enforcement is no longer active.");
        }
        if (original.version() != versions.enforcementVersion()) {
            throw AppealException.conflict("ENFORCEMENT_VERSION_CONFLICT",
                    "The original enforcement version is stale.");
        }
        long actualTargetVersion = currentTargetVersion(original);
        if (actualTargetVersion != versions.targetVersion()) {
            throw AppealException.conflict("TARGET_VERSION_CONFLICT", "The enforcement target version is stale.");
        }
        if (appeal.status() == Status.MODIFY_RECOMMENDED) {
            ReplacementProposal proposal = requiredReplacement(appeal);
            if (proposal.expectedTargetVersion() == null
                    || proposal.expectedTargetVersion() != versions.targetVersion()) {
                throw AppealException.conflict("TARGET_VERSION_CONFLICT",
                        "The stored replacement proposal was based on a stale target version.");
            }
        }
    }

    private void validateLocalPin(EnforcementService.StatePin pin, ResolutionVersions versions) {
        if (pin != null && pin.targetVersion() != versions.targetVersion()) {
            throw AppealException.conflict("TARGET_VERSION_CONFLICT",
                    "The enforcement target version is stale.");
        }
    }

    private Instant previewExpiresAt(AppealRow appeal, Instant previewedAt) {
        Instant expiresAt = previewedAt.plus(RESOLUTION_PREVIEW_TTL);
        if (appeal.status() != Status.MODIFY_RECOMMENDED) return expiresAt;
        Instant replacementExpiry = requiredReplacement(appeal).expiresAt();
        return replacementExpiry != null && replacementExpiry.isBefore(expiresAt)
                ? replacementExpiry : expiresAt;
    }

    private void ensureReplacementExecutable(AppealRow appeal, Instant executionAt) {
        if (appeal.status() != Status.MODIFY_RECOMMENDED) return;
        Instant expiresAt = requiredReplacement(appeal).expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(executionAt)) {
            throw AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_EXPIRED",
                    "The replacement proposal expired and cannot be executed.");
        }
    }

    private long currentTargetVersion(ResolvedEnforcement enforcement) {
        JsonNode target = enforcement.currentTarget() == null
                ? repository.currentTarget(enforcement.targetType(), enforcement.targetId())
                : enforcement.currentTarget();
        JsonNode version = target == null ? null : target.get("version");
        if (version == null || !version.canConvertToLong()) {
            throw AppealException.unavailable("Current enforcement target context is unavailable.");
        }
        return version.longValue();
    }

    private void ensureRecommended(AppealRow appeal) {
        if (FINAL.contains(appeal.status())) {
            throw AppealException.conflict("APPEAL_READ_ONLY", "The final appeal resolution is immutable.");
        }
        if (!RECOMMENDED.contains(appeal.status())) {
            throw AppealException.conflict("APPEAL_NOT_RESOLVABLE",
                    "A final review recommendation is required before resolution.");
        }
    }

    private FinalOutcome finalOutcome(Status status) {
        return switch (status) {
            case UPHOLD_RECOMMENDED -> FinalOutcome.UPHELD;
            case MODIFY_RECOMMENDED -> FinalOutcome.MODIFIED;
            case REVOKE_RECOMMENDED -> FinalOutcome.REVOKED;
            default -> throw AppealException.conflict("APPEAL_NOT_RESOLVABLE",
                    "A final review recommendation is required before resolution.");
        };
    }

    private void requireResolutionPermissions(AdminAuthorizationService.AdminAccessSnapshot access,
                                              AppealRow appeal) {
        if (!hasResolutionPermissions(access, appeal)) {
            throw AppealException.forbidden("APPEAL_RESOLUTION_PERMISSION_REQUIRED",
                    "Appeal resolution and target-specific enforcement authority are required.");
        }
    }

    private boolean hasResolutionPermissions(AdminAuthorizationService.AdminAccessSnapshot access,
                                             AppealRow appeal) {
        if (!access.has(AdminPermission.APPEAL_RESOLVE)) return false;
        if (appeal.status() == Status.UPHOLD_RECOMMENDED) return true;
        AdminPermission reinstate = switch (appeal.targetType()) {
            case USER -> AdminPermission.USER_REINSTATE;
            case BUSINESS -> AdminPermission.BUSINESS_REINSTATE;
            case LISTING -> AdminPermission.LISTING_REINSTATE;
        };
        if (!access.has(reinstate)) return false;
        if (appeal.status() != Status.MODIFY_RECOMMENDED || appeal.replacementActionType() == null) return true;
        AdminPermission create = switch (appeal.targetType()) {
            case USER -> switch (appeal.replacementActionType()) {
                case RESTRICT -> AdminPermission.USER_RESTRICT;
                case SUSPEND -> AdminPermission.USER_SUSPEND;
                case BAN -> AdminPermission.USER_BAN;
            };
            case BUSINESS -> switch (appeal.replacementActionType()) {
                case RESTRICT -> AdminPermission.BUSINESS_RESTRICT;
                case SUSPEND -> AdminPermission.BUSINESS_SUSPEND;
                case BAN -> AdminPermission.BUSINESS_BAN;
            };
            case LISTING -> AdminPermission.LISTING_SUSPEND;
        };
        return access.has(create);
    }

    private void requireResolution(AdminAuthorizationService.AdminAccessSnapshot access,
                                   AdminPermission permission, String message) {
        if (!access.has(permission)) {
            throw AppealException.forbidden("APPEAL_RESOLUTION_PERMISSION_REQUIRED", message);
        }
    }

    private String previewFingerprint(AppealRow appeal, ResolvedEnforcement original,
                                      ResolutionVersions versions, String ownerConfirmationToken) {
        String enforcementState = safe(ownerConfirmationToken, "missing-owner-confirmation");
        return sha256(String.join("|", appeal.id(), appeal.status().name(),
                Long.toString(versions.appealVersion()), original.id(), original.lifecycle(),
                Long.toString(versions.enforcementVersion()), Long.toString(versions.targetVersion()),
                replacementSignature(appeal), enforcementState));
    }

    private String resolutionRequestHash(AppealRow appeal, NormalizedResolution command, String actorId) {
        return sha256(String.join("|", appeal.id(), actorId,
                appeal.reviewOutcome() == null ? "" : appeal.reviewOutcome().name(),
                Long.toString(command.versions().appealVersion()),
                Long.toString(command.versions().enforcementVersion()),
                Long.toString(command.versions().targetVersion()), replacementSignature(appeal)));
    }

    private String replacementSignature(AppealRow appeal) {
        ReplacementProposal proposal = replacement(appeal);
        if (proposal == null) return "";
        return String.join("~", proposal.actionType().name(),
                proposal.scopes().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),
                String.valueOf(proposal.expiresAt()), proposal.reasonCode(), proposal.reason(),
                String.valueOf(proposal.expectedTargetVersion()));
    }

    private ReplacementProposal replacement(AppealRow row) {
        return row.replacementActionType() == null ? null : new ReplacementProposal(
                row.targetType(), row.targetId(), row.replacementActionType(), repository.replacementScopes(row.id()),
                row.replacementExpiresAt(), row.replacementReasonCode(), row.replacementReason(),
                row.replacementTargetVersion());
    }

    private ReplacementProposal requiredReplacement(AppealRow appeal) {
        ReplacementProposal proposal = replacement(appeal);
        if (proposal == null) {
            throw AppealException.conflict("APPEAL_REPLACEMENT_INVALID",
                    "The modify recommendation has no replacement proposal.");
        }
        return proposal;
    }

    private EnforcementSummary enforcementSummary(ResolvedEnforcement enforcement) {
        return new EnforcementSummary(enforcement.id(), enforcement.targetType(), enforcement.targetId(),
                enforcement.actionType(), enforcement.scopes(), enforcement.lifecycle(), enforcement.effectiveAt(),
                enforcement.expiresAt(), enforcement.version(), enforcement.createdAt(), enforcement.reasonCode(),
                enforcement.reason(), enforcement.caseId());
    }

    private ResolvedEnforcement resolvedOriginalSnapshot(ResolvedEnforcement original, FinalOutcome outcome,
                                                         ExecutionComputation execution) {
        return new ResolvedEnforcement(original.id(), original.targetType(), original.targetId(),
                original.safeLabel(), original.actionType(), original.scopes(),
                outcome == FinalOutcome.UPHELD ? original.lifecycle() : "REVOKED",
                original.effectiveAt(), original.expiresAt(), execution.originalEnforcementVersion(),
                original.createdAt(), original.reasonCode(), original.reason(), original.caseId(),
                original.individualOwnerId(), original.businessOwnerId(), original.currentTarget(),
                execution.currentEffectiveState(), original.productOwned());
    }

    private String currentEffectiveState(ResolvedEnforcement enforcement) {
        if (enforcement.productOwned()) {
            return safe(enforcement.currentEffectiveState(), "UNAVAILABLE");
        }
        return effectiveState(enforcements.evaluate(enforcement.targetType(), enforcement.targetId(), clock.instant()));
    }

    private String effectiveState(List<EffectiveRestriction> restrictions) {
        if (restrictions == null || restrictions.isEmpty()) return "CLEAR";
        ActionType strongest = restrictions.stream().map(EffectiveRestriction::actionType)
                .max(Comparator.comparingInt(ActionType::severity)).orElse(ActionType.RESTRICT);
        return switch (strongest) {
            case RESTRICT -> "RESTRICTED";
            case SUSPEND -> "SUSPENDED";
            case BAN -> "BANNED";
        };
    }

    private List<EffectiveRestriction> listingRestrictions(
            List<ListingAppealContextClient.ListingEffectiveRestriction> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> new EffectiveRestriction(scope(value.scope()),
                ActionType.valueOf(value.actionType()), value.enforcementActionId())).toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private String safeResolutionSummary(FinalOutcome outcome, String currentState) {
        return switch (outcome) {
            case UPHELD -> "The original enforcement remains in effect.";
            case MODIFIED -> "The original enforcement was replaced with an adjusted action.";
            case REVOKED -> "CLEAR".equals(currentState)
                    ? "The appealed enforcement was revoked."
                    : "The appealed enforcement was revoked; other restrictions remain in effect.";
        };
    }

    private String resolutionReasonCode(FinalOutcome outcome) {
        return switch (outcome) {
            case UPHELD -> "APPEAL_UPHELD";
            case MODIFIED -> "APPEAL_MODIFIED";
            case REVOKED -> "APPEAL_REVOKED";
        };
    }

    private String resolutionReason(AppealRow appeal, FinalOutcome outcome) {
        String prefix = switch (outcome) {
            case UPHELD -> "Appeal resolution upheld the original enforcement.";
            case MODIFIED -> "Appeal resolution replaced the original enforcement.";
            case REVOKED -> "Appeal resolution revoked the original enforcement.";
        };
        String reviewed = appeal.reviewReason();
        if (reviewed == null || reviewed.isBlank()) return prefix;
        String combined = prefix + " " + reviewed.trim().replaceAll("\\s+", " ");
        return combined.substring(0, Math.min(1000, combined.length()));
    }

    private long requiredVersion(Long value, String label) {
        if (value == null || value < 0) {
            throw AppealException.invalid("APPEAL_RESOLUTION_VERSION_REQUIRED",
                    label + " version is required.");
        }
        return value;
    }

    private String subKey(String operation, String appealId, String key) {
        return sha256(operation + "|" + appealId + "|" + key);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ReplacementProposal normalizeReplacement(ReviewOutcome outcome, ReplacementProposal raw,
                                                      ResolvedEnforcement enforcement) {
        if (outcome != ReviewOutcome.MODIFY_RECOMMENDED) {
            if (raw != null) throw AppealException.invalid("APPEAL_REPLACEMENT_NOT_ALLOWED",
                    "A replacement proposal is allowed only for a modify recommendation.");
            return null;
        }
        if (raw == null || raw.actionType() == null || raw.scopes() == null || raw.scopes().isEmpty()
                || raw.expectedTargetVersion() == null || raw.expectedTargetVersion() < 0) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_INVALID", "A complete replacement proposal is required.");
        }
        if (raw.targetType() != null && raw.targetType() != enforcement.targetType()
                || raw.targetId() != null && !raw.targetId().equals(enforcement.targetId())) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_TARGET_MISMATCH",
                    "Replacement proposal must target the original enforcement target.");
        }
        Set<Scope> allowed = switch (enforcement.targetType()) {
            case USER -> USER_SCOPES; case BUSINESS -> BUSINESS_SCOPES; case LISTING -> LISTING_SCOPES;
        };
        if (!allowed.containsAll(raw.scopes())) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_SCOPE_INVALID",
                    "Replacement scopes are not compatible with the enforcement target.");
        }
        if (enforcement.targetType() == TargetType.LISTING && raw.actionType() == ActionType.BAN) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_ACTION_INVALID", "Listings cannot receive a ban action.");
        }
        if (raw.expiresAt() != null && !raw.expiresAt().isAfter(clock.instant())) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_EXPIRY_INVALID", "Replacement expiry must be in the future.");
        }
        Set<Scope> canonicalScopes = switch (enforcement.targetType()) {
            case USER -> raw.actionType() == ActionType.BAN ? USER_SCOPES : Set.copyOf(raw.scopes());
            case BUSINESS -> raw.actionType() == ActionType.BAN ? BUSINESS_SCOPES : Set.copyOf(raw.scopes());
            case LISTING -> raw.actionType() == ActionType.SUSPEND ? LISTING_SCOPES : Set.copyOf(raw.scopes());
        };
        String replacementReasonCode = bounded(raw.reasonCode(), "Replacement reason code", 64, true)
                .toUpperCase(Locale.ROOT);
        if (!REPLACEMENT_REASON_CODE.matcher(replacementReasonCode).matches()) {
            throw AppealException.invalid("APPEAL_REPLACEMENT_REASON_CODE_INVALID",
                    "Replacement reason code must use a stable uppercase identifier.");
        }
        return new ReplacementProposal(enforcement.targetType(), enforcement.targetId(), raw.actionType(),
                Set.copyOf(canonicalScopes), raw.expiresAt(), replacementReasonCode,
                text(raw.reason(), "Replacement reason", 1000, true), raw.expectedTargetVersion());
    }

    private NormalizedCreate normalizeCreate(CreateAppealRequest request) {
        if (request == null || request.reasonCode() == null) {
            throw AppealException.invalid("APPEAL_REASON_REQUIRED", "Appeal reason is required.");
        }
        String explanation = text(request.explanation(), "Explanation", MAX_EXPLANATION,
                request.reasonCode() == ReasonCode.OTHER);
        if (request.safeEvidenceReferences() != null && !request.safeEvidenceReferences().isEmpty()) {
            throw AppealException.invalid("APPEAL_EVIDENCE_REFERENCE_UNSUPPORTED",
                    "Evidence references are not supported in this release. Include relevant details in the explanation.");
        }
        return new NormalizedCreate(request.reasonCode(), explanation);
    }

    private List<TimelineEntry> timeline(List<EventRow> values) {
        return values.stream().map(value -> new TimelineEntry(value.eventId(), value.occurredAt(), value.eventType(),
                value.actorType(), value.actorId(), value.actorName(), value.source(), value.previousState(),
                value.newState(), value.reasonCode(), value.reason(), value.correlationId(), value.requestId(),
                value.safeMetadata())).toList();
    }

    private EventRow adminEvent(String appealId, String type, User actor, Status previous, Status next,
                                String reasonCode, String reason, String correlationId) {
        return event(appealId, type, "PLATFORM_ADMIN", actor.getId(), safeName(actor), "HUMAN_ADMIN",
                previous.name(), next.name(), reasonCode, reason, correlation(correlationId), Map.of());
    }
    private EventRow event(String appealId, String type, String actorType, String actorId, String actorName,
                           String source, String previous, String next, String reasonCode, String reason,
                           String correlationId, Map<String,String> metadata) {
        return new EventRow(ulids.next(), appealId, type, clock.instant(), actorType, actorId, actorName, source,
                previous, next, reasonCode, reason, correlationId, ulids.next(), metadata);
    }

    private void ensureActive(ResolvedEnforcement enforcement) {
        if (!"ACTIVE".equals(enforcement.lifecycle())) {
            throw AppealException.conflict("ENFORCEMENT_NOT_APPEALABLE", "Only active enforcement can be appealed.");
        }
    }
    private void ensureOwned(AppealRow appeal, User actor) {
        if (appeal.assignedAdminId() == null) {
            throw AppealException.conflict("APPEAL_NOT_ASSIGNED", "Claim this appeal before changing it.");
        }
        if (!actor.getId().equals(appeal.assignedAdminId())) {
            throw AppealException.forbidden("APPEAL_ASSIGNED_TO_ANOTHER_ADMIN",
                    "This appeal is assigned to another reviewer.");
        }
    }
    private void conflict(AppealRow appeal) {
        if (RECOMMENDED.contains(appeal.status())) {
            throw AppealException.conflict("APPEAL_READ_ONLY", "The appeal recommendation is read-only.");
        }
        throw AppealException.conflict("APPEAL_VERSION_CONFLICT", "Appeal state changed. Reload before continuing.");
    }
    private void require(User actor, AdminPermission permission) {
        if (!authorization.accessFor(actor).has(permission)) {
            throw AppealException.forbidden("APPEAL_PERMISSION_REQUIRED", "Appeal permission is required.");
        }
    }
    private long version(Long value) { if (value == null || value < 0) throw AppealException.invalid("APPEAL_VERSION_REQUIRED", "Expected appeal version is required."); return value; }
    private String id(String label, String value) { try { return FixedLengthIds.requireTrimmed(label, value, 26); } catch (RuntimeException e) { throw AppealException.invalid("APPEAL_ID_INVALID", label + " is invalid."); } }
    private String normalizeSearch(String value) { if (value == null || value.isBlank()) return null; String v=value.trim(); if(v.length()>128) throw AppealException.invalid("APPEAL_SEARCH_INVALID","Search is too long."); return v; }
    private String normalizeSort(String value) { String v=value==null||value.isBlank()?"submittedAt,desc":value.trim(); if(!Set.of("submittedAt,desc","submittedAt,asc","updatedAt,desc","updatedAt,asc").contains(v)) throw AppealException.invalid("APPEAL_SORT_INVALID","Sort is invalid."); return v; }
    private String text(String value, String label, int max, boolean required) { String v=value==null?null:value.trim().replaceAll("\\s+"," "); if(required&&(v==null||v.isBlank())) throw AppealException.invalid("APPEAL_TEXT_REQUIRED",label+" is required."); if(v!=null&&v.length()>max) throw AppealException.invalid("APPEAL_TEXT_TOO_LONG",label+" is too long."); if(v!=null&&v.chars().anyMatch(ch->Character.isISOControl(ch))) throw AppealException.invalid("APPEAL_TEXT_INVALID",label+" contains unsupported characters."); return v==null||v.isBlank()?null:v; }
    private String bounded(String value,String label,int max,boolean required){return text(value,label,max,required);}
    private String safeName(User user) { return safe(user.getDisplayName(), "Marketplace user"); }
    private String safe(String value, String fallback) { return value==null||value.isBlank()?fallback:value.trim(); }
    private String correlation(String value) { return value==null||value.isBlank()?ulids.next():value; }
    private String supportReference(String appealId) { return "APL-" + appealId.substring(appealId.length()-8); }
    private String enforcementSupportReference(String actionId) { return "ENF-" + actionId.substring(actionId.length()-8); }
    private Scope scope(String value) { try { return Scope.valueOf(value); } catch (RuntimeException e) { throw AppealException.unavailable("Listing enforcement context is invalid."); } }

    private record NormalizedCreate(ReasonCode reasonCode, String explanation) { }
    private record ResolvedEnforcement(String id, TargetType targetType, String targetId, String safeLabel,
                                       ActionType actionType, Set<Scope> scopes, String lifecycle,
                                       Instant effectiveAt, Instant expiresAt, long version, Instant createdAt,
                                       String reasonCode, String reason, String caseId, String individualOwnerId,
                                       String businessOwnerId, JsonNode currentTarget,
                                       String currentEffectiveState, boolean productOwned) { }
    private record ResolutionVersions(long appealVersion, long enforcementVersion, long targetVersion) { }
    private record NormalizedResolution(ResolutionVersions versions, String previewToken,
                                        String idempotencyKey) { }
    private record PreviewComputation(String predictedState, List<EffectiveRestriction> restrictions,
                                      List<String> warnings, List<String> impactSummary,
                                      String ownerConfirmationToken) { }
    private record ExecutionComputation(long originalEnforcementVersion,
                                        String replacementEnforcementActionId,
                                        String currentEffectiveState) { }
    private record LocalPreview(List<EffectiveRestriction> restrictions, List<String> warnings,
                                List<String> impactSummary) { }
}
