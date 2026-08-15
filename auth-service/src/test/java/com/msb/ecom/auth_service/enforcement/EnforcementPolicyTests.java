package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnforcementPolicyTests {
    private static final String TARGET = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private final EnforcementPolicy policy = new EnforcementPolicy();

    @Test
    void severityOrderIsStable() {
        assertThat(ActionType.RESTRICT.severity()).isLessThan(ActionType.SUSPEND.severity());
        assertThat(ActionType.SUSPEND.severity()).isLessThan(ActionType.BAN.severity());
    }

    @Test
    void normalizesCompatibleUserCommandAndStableFingerprint() {
        CreateCommand command = command(TargetType.USER, Set.of(Scope.USER_SELLING), null, Map.of());
        EnforcementPolicy.NormalizedCreate first = policy.normalize(command, NOW);
        EnforcementPolicy.NormalizedCreate second = policy.normalize(command, NOW);
        assertThat(first.effectiveAt()).isEqualTo(NOW);
        assertThat(first.reasonCode()).isEqualTo("POLICY_ABUSE");
        assertThat(policy.fingerprint(first)).isEqualTo(policy.fingerprint(second)).hasSize(64);
    }

    @Test
    void rejectsEmptyAndCrossTargetScopes() {
        assertThatThrownBy(() -> policy.normalize(command(TargetType.USER, Set.of(), null, Map.of()), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
        assertThatThrownBy(() -> policy.normalize(
                command(TargetType.USER, Set.of(Scope.BUSINESS_NEW_SALES), null, Map.of()), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    @Test
    void rejectsFutureActivationAndInvalidExpiration() {
        assertThatThrownBy(() -> policy.normalize(command(TargetType.USER, Set.of(Scope.USER_LOGIN),
                NOW.plusSeconds(30), Map.of()), NOW)).isInstanceOf(EnforcementExceptions.Validation.class);
        CreateCommand invalid = new CreateCommand(TargetType.USER, TARGET, ActionType.SUSPEND,
                Set.of(Scope.USER_LOGIN), "POLICY_ABUSE", "Reason", null, NOW, NOW, 0L, "key", Map.of(), false);
        assertThatThrownBy(() -> policy.normalize(invalid, NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    @Test
    void acceptsOnlyAllowListedSafeMetadata() {
        assertThat(policy.normalize(command(TargetType.BUSINESS, Set.of(Scope.BUSINESS_NEW_SALES), null,
                Map.of("policyReference", "POL-42")), NOW).safeMetadata())
                .containsEntry("policyReference", "POL-42");
        assertThatThrownBy(() -> policy.normalize(command(TargetType.BUSINESS,
                Set.of(Scope.BUSINESS_NEW_SALES), null, Map.of("accessToken", "secret")), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    @Test
    void dryRunDoesNotRequireIdempotencyKeyButMutationDoes() {
        CreateCommand dryRun = new CreateCommand(TargetType.USER, TARGET, ActionType.RESTRICT,
                Set.of(Scope.USER_BUYING), "POLICY_ABUSE", "Reason", null, null, null, 0L, null, Map.of(), true);
        assertThat(policy.normalize(dryRun, NOW).idempotencyKey()).isNull();
        assertThatThrownBy(() -> policy.normalize(command(TargetType.USER,
                Set.of(Scope.USER_BUYING), null, Map.of(), null), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    private CreateCommand command(TargetType target, Set<Scope> scopes, Instant effective, Map<String, String> metadata) {
        return command(target, scopes, effective, metadata, "key-1");
    }

    private CreateCommand command(TargetType target, Set<Scope> scopes, Instant effective,
                                  Map<String, String> metadata, String key) {
        return new CreateCommand(target, TARGET, ActionType.SUSPEND, scopes, "policy_abuse", " Human reason ",
                null, effective, NOW.plusSeconds(3600), 0L, key, metadata, false);
    }
}
