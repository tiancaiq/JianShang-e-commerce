package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveEnforcementEvaluatorTests {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private final EffectiveEnforcementEvaluator evaluator = new EffectiveEnforcementEvaluator();

    @Test
    void strongestActionWinsPerScopeAndRevocationRevealsWeakerAction() {
        var restrict = candidate("restrict", ActionType.RESTRICT, Scope.USER_SELLING, null, null);
        var suspend = candidate("suspend", ActionType.SUSPEND, Scope.USER_SELLING, null, null);
        var buying = candidate("buying", ActionType.BAN, Scope.USER_BUYING, null, null);
        assertThat(evaluator.evaluate(List.of(restrict, suspend, buying), NOW))
                .extracting(result -> result.scope().name() + ":" + result.actionType().name())
                .containsExactly("USER_BUYING:BAN", "USER_SELLING:SUSPEND");
        var revokedSuspend = candidate("suspend", ActionType.SUSPEND, Scope.USER_SELLING, null, NOW.minusSeconds(1));
        assertThat(evaluator.evaluate(List.of(restrict, revokedSuspend), NOW).getFirst().actionType())
                .isEqualTo(ActionType.RESTRICT);
    }

    @Test
    void excludesExpiredActions() {
        assertThat(evaluator.evaluate(List.of(candidate("old", ActionType.BAN, Scope.USER_LOGIN,
                NOW.minusSeconds(1), null)), NOW)).isEmpty();
    }

    private EffectiveEnforcementEvaluator.Candidate candidate(String id, ActionType type, Scope scope,
                                                               Instant expiresAt, Instant revokedAt) {
        return new EffectiveEnforcementEvaluator.Candidate(id, type, Set.of(scope), NOW.minusSeconds(60),
                expiresAt, revokedAt);
    }
}
