package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveEnforcementEvaluatorTests {
    @Test
    void evaluatesListingScopesIndependentlyAndExcludesExpiredActions() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        var evaluator = new EffectiveEnforcementEvaluator();
        var restrict = new EffectiveEnforcementEvaluator.Candidate("r", ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), now.minusSeconds(2), null, null);
        var ban = new EffectiveEnforcementEvaluator.Candidate("b", ActionType.BAN,
                Set.of(Scope.LISTING_PURCHASABILITY), now.minusSeconds(2), null, null);
        var expired = new EffectiveEnforcementEvaluator.Candidate("x", ActionType.BAN,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), now.minusSeconds(2), now.minusSeconds(1), null);
        assertThat(evaluator.evaluate(List.of(restrict, ban, expired), now))
                .extracting(result -> result.scope().name() + ":" + result.actionType().name())
                .containsExactly("LISTING_PUBLIC_VISIBILITY:RESTRICT", "LISTING_PURCHASABILITY:BAN");
    }
}
