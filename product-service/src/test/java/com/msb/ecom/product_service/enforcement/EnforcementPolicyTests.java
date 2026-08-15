package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
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
    void listingVocabularyAndFingerprintAreStable() {
        assertThat(ActionType.RESTRICT.severity()).isLessThan(ActionType.SUSPEND.severity());
        assertThat(ActionType.SUSPEND.severity()).isLessThan(ActionType.BAN.severity());
        EnforcementPolicy.NormalizedCreate normalized = policy.normalize(valid(false), NOW);
        assertThat(normalized.scopes()).containsExactlyInAnyOrder(
                Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY);
        assertThat(policy.fingerprint(normalized)).hasSize(64);
    }

    @Test
    void rejectsNonListingTargetEmptyScopeAndFutureTime() {
        assertThatThrownBy(() -> policy.normalize(new CreateCommand(TargetType.USER, TARGET, ActionType.SUSPEND,
                Set.of(Scope.USER_LOGIN), "POLICY", "Reason", null, null, null, 0L, "key", Map.of(), false), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
        assertThatThrownBy(() -> policy.normalize(new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(), "POLICY", "Reason", null, null, null, 0L, "key", Map.of(), false), NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
        CreateCommand future = new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY", "Reason", null, NOW.plusSeconds(30),
                null, 0L, "key", Map.of(), false);
        assertThatThrownBy(() -> policy.normalize(future, NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    @Test
    void dryRunDoesNotRequireKeyAndUnsafeMetadataIsRejected() {
        assertThat(policy.normalize(valid(true), NOW).idempotencyKey()).isNull();
        CreateCommand unsafe = new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY", "Reason", null, null, null, 0L, "key",
                Map.of("token", "secret"), false);
        assertThatThrownBy(() -> policy.normalize(unsafe, NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class);
    }

    @Test
    void restrictPreservesSelectedScopeAndListingBanIsRejected() {
        CreateCommand restrict = new CreateCommand(TargetType.LISTING, TARGET, ActionType.RESTRICT,
                Set.of(Scope.LISTING_PURCHASABILITY), "POLICY", "Reason", null, null,
                NOW.plusSeconds(60), 1L, "restrict-key", Map.of(), false);
        assertThat(policy.normalize(restrict, NOW).scopes()).containsExactly(Scope.LISTING_PURCHASABILITY);

        CreateCommand ban = new CreateCommand(TargetType.LISTING, TARGET, ActionType.BAN,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY", "Reason", null, null,
                null, 1L, "ban-key", Map.of(), false);
        assertThatThrownBy(() -> policy.normalize(ban, NOW))
                .isInstanceOf(EnforcementExceptions.Validation.class)
                .hasMessageContaining("RESTRICT and SUSPEND");
    }

    private CreateCommand valid(boolean dryRun) {
        return new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY), "POLICY_ABUSE",
                "Human reason", null, null, NOW.plusSeconds(3600), 3L, dryRun ? null : "key-1",
                Map.of("policyReference", "POL-42"), dryRun);
    }
}
