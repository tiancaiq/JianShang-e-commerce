package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.BusinessMarketplaceCapabilities;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.CapabilityDecision;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesBatchRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesBatchResponse;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesResponse;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class BusinessCapabilityService {

    public static final Set<Scope> OPERATIONAL_SCOPES = Set.of(
            Scope.BUSINESS_LISTING_CREATION,
            Scope.BUSINESS_LISTING_PUBLICATION,
            Scope.BUSINESS_NEW_SALES);
    private static final int MAX_BATCH_SIZE = 100;

    private final EnforcementService enforcementService;
    private final JdbcTemplate jdbcTemplate;
    private final BusinessMembershipService membershipService;
    private final InternalCommerceAuthenticator internalAuthenticator;

    @Transactional(readOnly = true)
    public EvaluateCapabilitiesResponse evaluateInternal(
            String internalToken,
            EvaluateCapabilitiesRequest request) {
        internalAuthenticator.require(internalToken);
        if (request == null) {
            throw new EnforcementExceptions.Validation("Business capability request is required.");
        }
        Set<Scope> scopes = operationalScopes(request.scopes());
        String businessId = existingBusiness(request.businessId());
        return evaluate(businessId, scopes, Instant.now());
    }

    @Transactional(readOnly = true)
    public EvaluateCapabilitiesBatchResponse evaluateBatchInternal(
            String internalToken,
            EvaluateCapabilitiesBatchRequest request) {
        internalAuthenticator.require(internalToken);
        if (request == null || request.businessIds() == null || request.businessIds().isEmpty()) {
            throw new EnforcementExceptions.Validation("At least one business ID is required.");
        }
        if (request.businessIds().size() > MAX_BATCH_SIZE) {
            throw new EnforcementExceptions.Validation("At most 100 businesses can be evaluated at once.");
        }
        Set<Scope> scopes = operationalScopes(request.scopes());
        Instant evaluatedAt = Instant.now();
        List<EvaluateCapabilitiesResponse> results = request.businessIds().stream()
                .map(this::existingBusiness)
                .sorted()
                .map(id -> evaluate(id, scopes, evaluatedAt))
                .toList();
        return new EvaluateCapabilitiesBatchResponse(evaluatedAt, results);
    }

    @Transactional(readOnly = true)
    public BusinessMarketplaceCapabilities currentMemberCapabilities(String rawBusinessId) {
        String businessId = FixedLengthIds.requireTrimmed("Business ID", rawBusinessId, 26);
        membershipService.getCurrentMembership(businessId);
        EvaluateCapabilitiesResponse response = evaluate(businessId, OPERATIONAL_SCOPES, Instant.now());
        return new BusinessMarketplaceCapabilities(
                businessId,
                allowed(response, Scope.BUSINESS_LISTING_CREATION),
                allowed(response, Scope.BUSINESS_LISTING_PUBLICATION),
                allowed(response, Scope.BUSINESS_NEW_SALES),
                response.decisions().stream().filter(decision -> !decision.allowed()).toList());
    }

    private EvaluateCapabilitiesResponse evaluate(String businessId, Set<Scope> scopes, Instant now) {
        List<Result> actions = enforcementService.actions(TargetType.BUSINESS, businessId);
        Map<String, Result> byId = actions.stream().filter(action -> action.enforcementActionId() != null)
                .collect(Collectors.toMap(Result::enforcementActionId, Function.identity(), (first, ignored) -> first));
        Map<Scope, EffectiveRestriction> effective = enforcementService.evaluate(TargetType.BUSINESS, businessId, now)
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
        return new EvaluateCapabilitiesResponse(businessId, now, decisions);
    }

    private String existingBusiness(String rawBusinessId) {
        String businessId = FixedLengthIds.requireTrimmed("Business ID", rawBusinessId, 26);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from businesses where id = ?", Integer.class, businessId);
        if (count == null || count == 0) {
            throw new EnforcementExceptions.NotFound("Business was not found.");
        }
        return businessId;
    }

    private Set<Scope> operationalScopes(Set<Scope> scopes) {
        if (scopes == null || scopes.isEmpty() || !OPERATIONAL_SCOPES.containsAll(scopes)) {
            throw new EnforcementExceptions.Validation("Only operational business capabilities can be evaluated.");
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
