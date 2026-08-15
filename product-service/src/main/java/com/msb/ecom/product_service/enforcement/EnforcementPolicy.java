package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class EnforcementPolicy {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "noteCategory", "evidenceReference", "policyReference", "origin", "channel");

    NormalizedCreate normalize(CreateCommand command, Instant now) {
        if (command == null || command.targetType() != TargetType.LISTING || command.actionType() == null) {
            throw new EnforcementExceptions.Validation("Product Service owns only LISTING enforcement.");
        }
        if (command.actionType() != EnforcementContracts.ActionType.RESTRICT
                && command.actionType() != EnforcementContracts.ActionType.SUSPEND) {
            throw new EnforcementExceptions.Validation(
                    "LISTING enforcement supports only RESTRICT and SUSPEND actions.");
        }
        String targetId = FixedLengthIds.requireTrimmed("Target ID", command.targetId(), 26);
        if (command.scopes() == null || command.scopes().isEmpty()) {
            throw new EnforcementExceptions.Validation("At least one enforcement scope is required.");
        }
        LinkedHashSet<Scope> scopes = new LinkedHashSet<>();
        command.scopes().stream().sorted(Comparator.comparing(Enum::name)).forEach(scope -> {
            if (scope == null || scope.targetType() != TargetType.LISTING) {
                throw new EnforcementExceptions.Validation("Enforcement scope is incompatible with LISTING.");
            }
            scopes.add(scope);
        });
        if (command.actionType() == EnforcementContracts.ActionType.SUSPEND) {
            scopes.clear();
            scopes.add(Scope.LISTING_PUBLIC_VISIBILITY);
            scopes.add(Scope.LISTING_PURCHASABILITY);
        }
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        if (effectiveAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new EnforcementExceptions.Validation("Future enforcement activation is not supported.");
        }
        if (effectiveAt.isAfter(now)) effectiveAt = now;
        if (command.expiresAt() != null && !command.expiresAt().isAfter(effectiveAt)) {
            throw new EnforcementExceptions.Validation("Expiration must be later than the effective time.");
        }
        if (command.expectedTargetVersion() == null || command.expectedTargetVersion() < 0) {
            throw new EnforcementExceptions.Validation("Expected target version is required.");
        }
        String key = command.dryRun() ? optionalKey(command.idempotencyKey())
                : text("Idempotency key", command.idempotencyKey(), 128);
        return new NormalizedCreate(targetId, command.actionType(), Set.copyOf(scopes),
                reasonCode(command.reasonCode()), text("Reason", command.reason(), 1000),
                optionalId(command.caseId()), effectiveAt, command.expiresAt(), command.expectedTargetVersion(),
                key, metadata(command.safeMetadata()), command.dryRun());
    }

    NormalizedRevoke normalize(RevokeCommand command) {
        if (command == null || command.expectedEnforcementVersion() == null
                || command.expectedEnforcementVersion() < 0) {
            throw new EnforcementExceptions.Validation("Expected enforcement version is required.");
        }
        String key = command.dryRun() ? optionalKey(command.idempotencyKey())
                : text("Idempotency key", command.idempotencyKey(), 128);
        return new NormalizedRevoke(FixedLengthIds.requireTrimmed(
                "Enforcement action ID", command.enforcementActionId(), 26), command.expectedEnforcementVersion(),
                reasonCode(command.reasonCode()), text("Reason", command.reason(), 1000), key,
                metadata(command.safeMetadata()), command.dryRun());
    }

    String fingerprint(NormalizedCreate command) {
        return hash(String.join("|", "CREATE", "LISTING", command.targetId(), command.actionType().name(),
                command.scopes().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse(""),
                command.reasonCode(), command.reason(), value(command.caseId()), command.effectiveAt().toString(),
                value(command.expiresAt()), Long.toString(command.expectedTargetVersion()), metadataText(command.safeMetadata())));
    }

    String fingerprint(NormalizedRevoke command) {
        return hash(String.join("|", "REVOKE", command.enforcementActionId(),
                Long.toString(command.expectedEnforcementVersion()), command.reasonCode(), command.reason(),
                metadataText(command.safeMetadata())));
    }

    private Map<String, String> metadata(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.size() > 20) throw new EnforcementExceptions.Validation("Safe metadata supports at most 20 entries.");
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!ALLOWED_METADATA_KEYS.contains(entry.getKey())) {
                throw new EnforcementExceptions.Validation("Metadata key is not allow-listed: " + entry.getKey());
            }
            safe.put(entry.getKey(), text("Metadata value", entry.getValue(), 500));
        });
        return Map.copyOf(safe);
    }

    private String reasonCode(String value) {
        String code = text("Reason code", value, 64).toUpperCase(Locale.ROOT);
        if (!REASON_CODE.matcher(code).matches()) {
            throw new EnforcementExceptions.Validation("Reason code must use stable uppercase identifiers.");
        }
        return code;
    }

    private String optionalId(String value) {
        return value == null || value.isBlank() ? null : FixedLengthIds.requireTrimmed("Case ID", value, 26);
    }
    private String optionalKey(String value) { return value == null || value.isBlank() ? null : text("Idempotency key", value, 128); }
    private String text(String label, String value, int max) {
        if (value == null || value.isBlank()) throw new EnforcementExceptions.Validation(label + " is required.");
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > max) throw new EnforcementExceptions.Validation(label + " is too long.");
        return normalized;
    }
    private String metadataText(Map<String, String> metadata) {
        List<String> values = new ArrayList<>(); metadata.forEach((k, v) -> values.add(k + "=" + v));
        return String.join("&", values);
    }
    private String value(Object value) { return value == null ? "" : value.toString(); }
    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    record NormalizedCreate(String targetId, EnforcementContracts.ActionType actionType, Set<Scope> scopes,
            String reasonCode, String reason, String caseId, Instant effectiveAt, Instant expiresAt,
            long expectedTargetVersion, String idempotencyKey, Map<String, String> safeMetadata, boolean dryRun) { }
    record NormalizedRevoke(String enforcementActionId, long expectedEnforcementVersion, String reasonCode,
            String reason, String idempotencyKey, Map<String, String> safeMetadata, boolean dryRun) { }
}
