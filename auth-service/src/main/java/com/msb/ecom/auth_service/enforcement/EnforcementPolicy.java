package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.common.core.validation.FixedLengthIds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class EnforcementPolicy {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "noteCategory", "evidenceReference", "policyReference", "origin", "channel");

    NormalizedCreate normalize(CreateCommand command, Instant now) {
        if (command == null || command.targetType() == null || command.actionType() == null) {
            throw new EnforcementExceptions.Validation("Target type and action type are required.");
        }
        if (command.targetType() == TargetType.LISTING) {
            throw new EnforcementExceptions.Validation("Auth Service owns only USER and BUSINESS enforcement.");
        }
        String targetId = FixedLengthIds.requireTrimmed("Target ID", command.targetId(), 26);
        LinkedHashSet<Scope> scopes = normalizedScopes(command.targetType(), command.scopes());
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        if (effectiveAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new EnforcementExceptions.Validation("Future enforcement activation is not supported.");
        }
        if (effectiveAt.isAfter(now)) {
            effectiveAt = now;
        }
        if (command.expiresAt() != null && !command.expiresAt().isAfter(effectiveAt)) {
            throw new EnforcementExceptions.Validation("Expiration must be later than the effective time.");
        }
        Long expectedVersion = command.expectedTargetVersion();
        if (expectedVersion == null || expectedVersion < 0) {
            throw new EnforcementExceptions.Validation("Expected target version is required.");
        }
        String reasonCode = reasonCode(command.reasonCode());
        String reason = requiredText("Reason", command.reason(), 1000);
        String caseId = optionalId(command.caseId());
        String idempotencyKey = command.dryRun() ? optionalKey(command.idempotencyKey())
                : requiredText("Idempotency key", command.idempotencyKey(), 128);
        Map<String, String> metadata = metadata(command.safeMetadata());
        return new NormalizedCreate(command.targetType(), targetId, command.actionType(), Set.copyOf(scopes),
                reasonCode, reason, caseId, effectiveAt, command.expiresAt(), expectedVersion,
                idempotencyKey, metadata, command.dryRun());
    }

    NormalizedRevoke normalize(RevokeCommand command) {
        if (command == null) {
            throw new EnforcementExceptions.Validation("Revocation command is required.");
        }
        String actionId = FixedLengthIds.requireTrimmed("Enforcement action ID", command.enforcementActionId(), 26);
        if (command.expectedEnforcementVersion() == null || command.expectedEnforcementVersion() < 0) {
            throw new EnforcementExceptions.Validation("Expected enforcement version is required.");
        }
        String key = command.dryRun() ? optionalKey(command.idempotencyKey())
                : requiredText("Idempotency key", command.idempotencyKey(), 128);
        return new NormalizedRevoke(actionId, command.expectedEnforcementVersion(),
                reasonCode(command.reasonCode()), requiredText("Reason", command.reason(), 1000),
                key, metadata(command.safeMetadata()), command.dryRun());
    }

    String fingerprint(NormalizedCreate command) {
        List<String> scopes = command.scopes().stream().map(Enum::name).sorted().toList();
        return sha256(String.join("|", "CREATE", command.targetType().name(), command.targetId(),
                command.actionType().name(), String.join(",", scopes), command.reasonCode(), command.reason(),
                nullSafe(command.caseId()), command.effectiveAt().toString(), nullSafe(command.expiresAt()),
                Long.toString(command.expectedTargetVersion()), metadataString(command.safeMetadata())));
    }

    String fingerprint(NormalizedRevoke command) {
        return sha256(String.join("|", "REVOKE", command.enforcementActionId(),
                Long.toString(command.expectedEnforcementVersion()), command.reasonCode(), command.reason(),
                metadataString(command.safeMetadata())));
    }

    private LinkedHashSet<Scope> normalizedScopes(TargetType targetType, Set<Scope> rawScopes) {
        if (rawScopes == null || rawScopes.isEmpty()) {
            throw new EnforcementExceptions.Validation("At least one enforcement scope is required.");
        }
        LinkedHashSet<Scope> scopes = new LinkedHashSet<>();
        rawScopes.stream().sorted(Comparator.comparing(Enum::name)).forEach(scope -> {
            if (scope == null || scope.targetType() != targetType) {
                throw new EnforcementExceptions.Validation("Enforcement scope is incompatible with the target type.");
            }
            scopes.add(scope);
        });
        return scopes;
    }

    private Map<String, String> metadata(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > 20) {
            throw new EnforcementExceptions.Validation("Safe metadata supports at most 20 entries.");
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            if (key == null || !METADATA_KEY.matcher(key).matches() || !ALLOWED_METADATA_KEYS.contains(key)) {
                throw new EnforcementExceptions.Validation("Metadata key is not allow-listed: " + key);
            }
            safe.put(key, requiredText("Metadata value", entry.getValue(), 500));
        });
        return Map.copyOf(safe);
    }

    private String metadataString(Map<String, String> metadata) {
        List<String> values = new ArrayList<>();
        metadata.forEach((key, value) -> values.add(key + "=" + value));
        return String.join("&", values);
    }

    private String reasonCode(String value) {
        String normalized = requiredText("Reason code", value, 64).toUpperCase(java.util.Locale.ROOT);
        if (!REASON_CODE.matcher(normalized).matches()) {
            throw new EnforcementExceptions.Validation("Reason code must use stable uppercase identifiers.");
        }
        return normalized;
    }

    private String optionalId(String value) {
        return value == null || value.isBlank() ? null : FixedLengthIds.requireTrimmed("Case ID", value, 26);
    }

    private String optionalKey(String value) {
        return value == null || value.isBlank() ? null : requiredText("Idempotency key", value, 128);
    }

    private String requiredText(String label, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new EnforcementExceptions.Validation(label + " is required.");
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            throw new EnforcementExceptions.Validation(label + " is too long.");
        }
        return normalized;
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record NormalizedCreate(
            TargetType targetType, String targetId, ActionType actionType, Set<Scope> scopes,
            String reasonCode, String reason, String caseId, Instant effectiveAt, Instant expiresAt,
            long expectedTargetVersion, String idempotencyKey, Map<String, String> safeMetadata, boolean dryRun) {
    }

    record NormalizedRevoke(
            String enforcementActionId, long expectedEnforcementVersion, String reasonCode, String reason,
            String idempotencyKey, Map<String, String> safeMetadata, boolean dryRun) {
    }
}
