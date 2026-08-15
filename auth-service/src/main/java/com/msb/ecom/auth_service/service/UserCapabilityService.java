package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminUserContracts.CapabilityDecision;
import com.msb.ecom.auth_service.dto.AdminUserContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.EvaluateCapabilitiesResponse;
import com.msb.ecom.auth_service.dto.AdminUserContracts.UserMarketplaceCapabilities;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserCapabilityService {

    private final EnforcementService enforcementService;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final InternalCommerceAuthenticator internalAuthenticator;

    @Transactional(readOnly = true)
    public EvaluateCapabilitiesResponse evaluateInternal(
            String internalToken,
            EvaluateCapabilitiesRequest request) {
        internalAuthenticator.require(internalToken);
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new EnforcementExceptions.Validation("User ID is required.");
        }
        User user = userRepository.findById(request.userId().trim())
                .orElseThrow(() -> new EnforcementExceptions.NotFound("User was not found."));
        return evaluate(user.getId(), operationalScopes(request.scopes()));
    }

    @Transactional(readOnly = true)
    public UserMarketplaceCapabilities currentUserCapabilities() {
        User user = authService.ensureUserEntity();
        EvaluateCapabilitiesResponse response = evaluate(user.getId(), AdminUserService.OPERATIONAL_SCOPES);
        boolean buying = allowed(response, Scope.USER_BUYING);
        boolean selling = allowed(response, Scope.USER_SELLING);
        return new UserMarketplaceCapabilities(
                buying,
                selling,
                true,
                true,
                response.decisions().stream().filter(decision -> !decision.allowed()).toList());
    }

    @Transactional(readOnly = true)
    public void requireSellingAllowed(String userId) {
        CapabilityDecision decision = evaluate(userId, Set.of(Scope.USER_SELLING)).decisions().getFirst();
        if (!decision.allowed()) {
            throw new UserCapabilityRestrictedException(decision);
        }
    }

    private EvaluateCapabilitiesResponse evaluate(String userId, Set<Scope> scopes) {
        Instant now = Instant.now();
        List<Result> actions = enforcementService.actions(TargetType.USER, userId);
        Map<String, Result> byId = actions.stream().filter(action -> action.enforcementActionId() != null)
                .collect(Collectors.toMap(Result::enforcementActionId, Function.identity(), (first, ignored) -> first));
        Map<Scope, EffectiveRestriction> effective = enforcementService.evaluate(TargetType.USER, userId, now)
                .stream().collect(Collectors.toMap(EffectiveRestriction::scope, Function.identity()));
        List<CapabilityDecision> decisions = scopes.stream().sorted(Comparator.comparing(Enum::name)).map(scope -> {
            EffectiveRestriction restriction = effective.get(scope);
            if (restriction == null) {
                return new CapabilityDecision(scope, true, null, null, null, null, null);
            }
            Result action = byId.get(restriction.enforcementActionId());
            return new CapabilityDecision(
                    scope,
                    false,
                    restriction.actionType(),
                    restriction.enforcementActionId(),
                    action == null ? null : action.effectiveAt(),
                    action == null ? null : action.expiresAt(),
                    supportReference(restriction.enforcementActionId()));
        }).toList();
        return new EvaluateCapabilitiesResponse(userId, now, decisions);
    }

    private Set<Scope> operationalScopes(Set<Scope> scopes) {
        if (scopes == null || scopes.isEmpty() || !AdminUserService.OPERATIONAL_SCOPES.containsAll(scopes)) {
            throw new EnforcementExceptions.Validation("Only operational user capabilities can be evaluated.");
        }
        return Set.copyOf(scopes);
    }

    private boolean allowed(EvaluateCapabilitiesResponse response, Scope scope) {
        return response.decisions().stream().filter(decision -> decision.scope() == scope)
                .findFirst().map(CapabilityDecision::allowed).orElse(true);
    }

    private String supportReference(String actionId) {
        return actionId == null ? null : "ENF-" + actionId.substring(Math.max(0, actionId.length() - 8));
    }
}
