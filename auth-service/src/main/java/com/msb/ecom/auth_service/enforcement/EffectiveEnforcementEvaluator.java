package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EffectiveEnforcementEvaluator {
    public List<EffectiveRestriction> evaluate(List<Candidate> candidates, Instant at) {
        Map<Scope, Candidate> strongest = new EnumMap<>(Scope.class);
        candidates.stream().filter(candidate -> candidate.activeAt(at)).forEach(candidate ->
                candidate.scopes().forEach(scope -> strongest.merge(scope, candidate,
                        (left, right) -> right.actionType().severity() > left.actionType().severity() ? right : left)));
        return strongest.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                .map(entry -> new EffectiveRestriction(entry.getKey(), entry.getValue().actionType(),
                        entry.getValue().enforcementActionId())).toList();
    }

    public record Candidate(String enforcementActionId, ActionType actionType, Set<Scope> scopes,
                            Instant effectiveAt, Instant expiresAt, Instant revokedAt) {
        boolean activeAt(Instant at) {
            return !effectiveAt.isAfter(at) && (expiresAt == null || expiresAt.isAfter(at)) && revokedAt == null;
        }
    }
}
