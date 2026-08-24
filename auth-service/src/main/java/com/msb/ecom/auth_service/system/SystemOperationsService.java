package com.msb.ecom.auth_service.system;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.msb.ecom.auth_service.system.SystemContracts.*;

@Service
@RequiredArgsConstructor
public class SystemOperationsService {
    private static final Pattern TARGET = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private final AuthService auth;
    private final AdminAuthorizationService authorization;
    private final SystemOperationsClient client;
    private final SystemOperationsRepository repository;
    private final UlidGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SystemSummary summary() {
        authorized(AdminPermission.SYSTEM_READ);
        List<ServiceHealth> health = client.health();
        List<SourceSnapshot> sources = client.snapshots();
        SystemOperationsSummaryAssembler.Signals signals =
                SystemOperationsSummaryAssembler.assemble(health, sources);
        return new SystemSummary(clock.instant(), new CountPair(signals.healthyServices(),
                signals.degradedServices() + signals.unavailableServices()),
                new CountPair(signals.failedJobs(), signals.retryableJobs()),
                new CountPair(signals.failedOutboxEvents(), signals.deadLetterEvents()),
                signals.reconciliationIssues(), signals.inventoryIssues(),
                signals.searchIndexFailures(), signals.featureWarnings(), signals.sourceWarnings(),
                repository.recent(8));
    }

    public List<ServiceHealth> health() {
        authorized(AdminPermission.SYSTEM_READ);
        return client.health();
    }

    public Page<JobSummary> jobs(String owner, String type, String status, Boolean retryable,
            int page, int size) {
        authorized(AdminPermission.SYSTEM_READ);
        Predicate<JobSummary> filter = value -> matches(owner, value.ownerService())
                && matches(type, value.jobType()) && matchesJobStatus(status, value.status())
                && (retryable == null || retryable == value.retryable());
        return page(jobs(client.snapshots()).stream().filter(filter)
                .sorted(Comparator.comparing(JobSummary::createdAt).reversed()).toList(), page, size);
    }

    public JobSummary job(String id) {
        authorized(AdminPermission.SYSTEM_READ);
        String target = target(id);
        return jobs(client.snapshots()).stream().filter(value -> target.equals(value.jobId()))
                .findFirst().orElseThrow(SystemOperationsException::notFound);
    }

    public Page<OutboxSummary> outbox(String owner, String eventType, String status,
            String correlationId, int page, int size) {
        authorized(AdminPermission.SYSTEM_READ);
        Predicate<OutboxSummary> filter = value -> matches(owner, value.ownerService())
                && matches(eventType, value.eventType()) && matchesOutboxStatus(status, value.status())
                && matches(correlationId, value.correlationId());
        return page(outbox(client.snapshots()).stream().filter(filter)
                .sorted(Comparator.comparing(OutboxSummary::createdAt).reversed()).toList(), page, size);
    }

    public OutboxSummary outboxEvent(String id) {
        authorized(AdminPermission.SYSTEM_READ);
        String target = target(id);
        return outbox(client.snapshots()).stream().filter(value -> target.equals(value.eventId()))
                .findFirst().orElseThrow(SystemOperationsException::notFound);
    }

    public Page<ReconciliationIssue> reconciliation(String type, String status, int page, int size) {
        authorized(AdminPermission.SYSTEM_READ);
        return page(reconciliation(client.snapshots()).stream()
                .filter(value -> matches(type, value.issueType()) && matches(status, value.status()))
                .sorted(Comparator.comparing(ReconciliationIssue::lastCheckedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList(), page, size);
    }

    public Page<InventoryIssue> inventory(String issueType, int page, int size) {
        authorized(AdminPermission.SYSTEM_READ);
        return page(inventory(client.snapshots()).stream()
                .filter(value -> matches(issueType, value.issueType()))
                .sorted(Comparator.comparingLong(InventoryIssue::ageSeconds).reversed()).toList(), page, size);
    }

    public List<SearchStatus> search() {
        authorized(AdminPermission.SYSTEM_READ);
        return searches(client.snapshots());
    }

    public List<FeatureState> features() {
        authorized(AdminPermission.FEATURE_READ);
        return features(client.snapshots());
    }

    public List<OperationEvent> recent() {
        authorized(AdminPermission.SYSTEM_READ);
        return repository.recent(50);
    }

    public MaintenancePreview preview(String targetType, String targetId, String commandType,
            MaintenanceRequest request, String correlationId) {
        permission(commandType);
        Validated input = validate(targetId, request, null, false);
        OwnerAction result = client.action(owner(targetType, input.targetId()), targetType,
                input.targetId(), commandType, true, input.reason(), "preview-" + ids.next(),
                correlation(correlationId));
        return preview(result);
    }

    public MaintenanceResult execute(String targetType, String targetId, String commandType,
            MaintenanceRequest request, String headerKey, String correlationId) {
        User actor = permission(commandType);
        Validated input = validate(targetId, request, headerKey, true);
        String key = key(headerKey == null ? input.idempotencyKey() : headerKey);
        String safeCorrelation = correlation(correlationId);
        String requestHash = hash(targetType + "|" + input.targetId() + "|" + commandType
                + "|" + input.reason());
        SystemOperationsRepository.CommandRow existing = repository.command(
                actor.getId(), commandType, key, false).orElse(null);
        if (existing != null) return replay(existing, requestHash, targetType, commandType);
        String ownerService = owner(targetType, input.targetId());

        Instant now = clock.instant();
        String commandId = ids.next();
        Boolean inserted = transactions.execute(status -> repository.insertCommand(
                new SystemOperationsRepository.CommandInsert(commandId, actor.getId(), commandType,
                        ownerService, targetType, input.targetId(), key, requestHash, input.reason(),
                        safeCorrelation, now)));
        if (!Boolean.TRUE.equals(inserted)) {
            return replay(repository.command(actor.getId(), commandType, key, false)
                    .orElseThrow(), requestHash, targetType, commandType);
        }

        OwnerAction action;
        try {
            action = client.action(ownerService, targetType,
                    input.targetId(), commandType, false, input.reason(), key, safeCorrelation);
        } catch (RuntimeException exception) {
            audit(actor, commandId, targetType, input.targetId(), commandType, input.reason(),
                    "REJECTED", safeCorrelation, "REJECTED");
            throw exception;
        }
        String outcome = action.result() == null ? "ACCEPTED" : action.result();
        audit(actor, commandId, targetType, input.targetId(), commandType, input.reason(),
                outcome, safeCorrelation, "COMPLETED");
        return new MaintenanceResult(commandId, targetType, input.targetId(),
                action.ownerService(), commandType, outcome, false, now,
                action.safeSummary() == null ? "The owning worker accepted the recovery request." : action.safeSummary());
    }

    private void audit(User actor, String commandId, String targetType, String targetId,
            String commandType, String reason, String outcome, String correlation, String state) {
        transactions.executeWithoutResult(status -> {
            repository.complete(commandId, state, outcome, clock.instant());
            repository.event(new SystemOperationsRepository.EventInsert(ids.next(), commandId,
                    eventType(commandType), actor.getId(), targetType, targetId, reason, outcome,
                    correlation, ids.next(), Map.of("commandType", commandType), clock.instant()));
        });
    }

    private MaintenanceResult replay(SystemOperationsRepository.CommandRow command, String hash,
            String targetType, String commandType) {
        if (!Objects.equals(command.requestHash(), hash)) {
            throw SystemOperationsException.conflict("SYSTEM_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was reused with changed recovery content.");
        }
        if ("IN_PROGRESS".equals(command.state())) {
            throw SystemOperationsException.conflict("SYSTEM_OPERATION_ALREADY_RUNNING",
                    "The same recovery request is still being accepted by the owning service.");
        }
        return new MaintenanceResult(command.id(), targetType, command.targetId(),
                command.ownerService(), commandType, command.result(), true,
                command.createdAt(), "The original recovery request was returned without creating another action.");
    }

    private MaintenancePreview preview(OwnerAction result) {
        return new MaintenancePreview(result.targetType(), result.targetId(), result.ownerService(),
                result.commandType(), result.currentState(), result.allowed(), result.denialReason(),
                safe(result.knownDependencies()), safe(result.warnings()), result.expectedOperation(),
                result.customerImpact());
    }

    private User permission(String commandType) {
        AdminPermission permission = "REINDEX_LISTING".equals(commandType)
                ? AdminPermission.SEARCH_MAINTENANCE : AdminPermission.SYSTEM_RETRY;
        return authorized(permission);
    }

    private User authorized(AdminPermission permission) {
        User user = auth.ensureUserEntity();
        authorization.requirePermission(user, permission);
        return user;
    }

    private String owner(String targetType, String targetId) {
        if ("SEARCH_LISTING".equals(targetType)) return "PRODUCT";
        if ("JOB".equals(targetType)) {
            return jobs(client.snapshots()).stream().filter(value -> targetId.equals(value.jobId()))
                    .map(JobSummary::ownerService).findFirst().orElseThrow(SystemOperationsException::notFound);
        }
        if ("OUTBOX_EVENT".equals(targetType)) {
            return outbox(client.snapshots()).stream().filter(value -> targetId.equals(value.eventId()))
                    .map(OutboxSummary::ownerService).findFirst().orElseThrow(SystemOperationsException::notFound);
        }
        throw SystemOperationsException.notFound();
    }

    private Validated validate(String rawTarget, MaintenanceRequest request, String headerKey,
            boolean keyRequired) {
        String normalized = target(rawTarget);
        String reason = request == null || request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.length() < 10 || reason.length() > 1000) {
            throw new IllegalArgumentException("A recovery reason between 10 and 1000 characters is required.");
        }
        String idempotency = request == null ? null : request.idempotencyKey();
        if (keyRequired && (headerKey == null || headerKey.isBlank())
                && (idempotency == null || idempotency.isBlank())) {
            throw new IllegalArgumentException("Idempotency-Key is required.");
        }
        return new Validated(normalized, reason, idempotency);
    }

    private String target(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!TARGET.matcher(normalized).matches()) throw new IllegalArgumentException("Target ID is invalid.");
        return normalized;
    }

    private String key(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!KEY.matcher(normalized).matches()) throw new IllegalArgumentException("Idempotency-Key is invalid.");
        return normalized;
    }

    private String correlation(String value) {
        return value == null || value.isBlank() ? ids.next() : value;
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String eventType(String command) {
        return switch (command) {
            case "RETRY_JOB" -> "JOB_RETRY_REQUESTED";
            case "RETRY_OUTBOX" -> "OUTBOX_RETRY_REQUESTED";
            case "REINDEX_LISTING" -> "SEARCH_REINDEX_REQUESTED";
            default -> "SYSTEM_RECOVERY_REQUESTED";
        };
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank()
                || expected.trim().equalsIgnoreCase(actual == null ? "" : actual);
    }

    /** Supports the bounded failure union used by the analytics drill-down. */
    private boolean matchesJobStatus(String expected, String actual) {
        if (expected != null && "FAILURE".equalsIgnoreCase(expected.trim())) {
            return List.of("FAILED", "DEAD_LETTER", "TERMINAL")
                    .contains(actual == null ? "" : actual.toUpperCase());
        }
        return matches(expected, actual);
    }

    /** Supports the bounded failure union used by the analytics drill-down. */
    private boolean matchesOutboxStatus(String expected, String actual) {
        if (expected != null && "FAILURE".equalsIgnoreCase(expected.trim())) {
            return List.of("FAILED", "DEAD_LETTER")
                    .contains(actual == null ? "" : actual.toUpperCase());
        }
        return matches(expected, actual);
    }

    private List<JobSummary> jobs(List<SourceSnapshot> sources) {
        return sources.stream().flatMap(value -> safe(value.jobs()).stream()).toList();
    }

    private List<OutboxSummary> outbox(List<SourceSnapshot> sources) {
        return sources.stream().flatMap(value -> safe(value.outbox()).stream()).toList();
    }

    private List<ReconciliationIssue> reconciliation(List<SourceSnapshot> sources) {
        return sources.stream().flatMap(value -> safe(value.reconciliation()).stream()).toList();
    }

    private List<InventoryIssue> inventory(List<SourceSnapshot> sources) {
        return sources.stream().flatMap(value -> safe(value.inventory()).stream()).toList();
    }

    private List<SearchStatus> searches(List<SourceSnapshot> sources) {
        return sources.stream().map(SourceSnapshot::search).filter(Objects::nonNull).toList();
    }

    private List<FeatureState> features(List<SourceSnapshot> sources) {
        return sources.stream().flatMap(value -> safe(value.features()).stream()).toList();
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    private <T> Page<T> page(List<T> values, int rawPage, int rawSize) {
        int page = Math.max(0, rawPage);
        int size = Math.max(1, Math.min(rawSize, 100));
        int from = Math.min(values.size(), page * size);
        int to = Math.min(values.size(), from + size);
        int pages = values.isEmpty() ? 0 : (int) Math.ceil(values.size() / (double) size);
        return new Page<>(List.copyOf(values.subList(from, to)), page, size, values.size(), pages);
    }

    private record Validated(String targetId, String reason, String idempotencyKey) { }
}
