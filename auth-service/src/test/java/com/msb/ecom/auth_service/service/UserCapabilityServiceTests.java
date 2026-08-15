package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminUserContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCapabilityServiceTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String ACTION_ID = "01E00000000000000000000001";
    private static final Instant NOW = Instant.parse("2026-08-15T01:00:00Z");

    private final EnforcementService enforcement = mock(EnforcementService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final InternalCommerceAuthenticator internal = mock(InternalCommerceAuthenticator.class);
    private final UserCapabilityService service = new UserCapabilityService(enforcement, users, auth, internal);

    @Test
    void internalDecisionReturnsOnlySafeEffectiveCapabilityFacts() {
        User user = User.create(USER_ID, "subject", "buyer@example.test", true, "Buyer", "buyer");
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(enforcement.actions(TargetType.USER, USER_ID)).thenReturn(List.of(action()));
        when(enforcement.evaluate(any(), any(), any())).thenReturn(List.of(
                new EffectiveRestriction(Scope.USER_BUYING, ActionType.SUSPEND, ACTION_ID)));

        var response = service.evaluateInternal(
                "service-token",
                new EvaluateCapabilitiesRequest(USER_ID, Set.of(Scope.USER_BUYING, Scope.USER_SELLING)));

        verify(internal).require("service-token");
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.decisions()).hasSize(2);
        assertThat(response.decisions()).filteredOn(decision -> decision.scope() == Scope.USER_BUYING)
                .singleElement().satisfies(decision -> {
                    assertThat(decision.allowed()).isFalse();
                    assertThat(decision.effectiveAction()).isEqualTo(ActionType.SUSPEND);
                    assertThat(decision.enforcementActionId()).isEqualTo(ACTION_ID);
                    assertThat(decision.supportReference()).isEqualTo("ENF-00000001");
                });
        assertThat(response.decisions()).filteredOn(decision -> decision.scope() == Scope.USER_SELLING)
                .singleElement().extracting(decision -> decision.allowed()).isEqualTo(true);
    }

    @Test
    void currentUserSummaryKeepsReservedCapabilitiesAvailableAndReasonsPrivate() {
        User user = User.create(USER_ID, "subject", "buyer@example.test", true, "Buyer", "buyer");
        when(auth.ensureUserEntity()).thenReturn(user);
        when(enforcement.actions(TargetType.USER, USER_ID)).thenReturn(List.of());
        when(enforcement.evaluate(any(), any(), any())).thenReturn(List.of());

        var response = service.currentUserCapabilities();

        assertThat(response.buyingAllowed()).isTrue();
        assertThat(response.sellingAllowed()).isTrue();
        assertThat(response.messagingAllowed()).isTrue();
        assertThat(response.marketplaceAccessAllowed()).isTrue();
        assertThat(response.restrictions()).isEmpty();
    }

    private Result action() {
        return new Result(
                ACTION_ID,
                TargetType.USER,
                USER_ID,
                ActionType.SUSPEND,
                Set.of(Scope.USER_BUYING),
                LifecycleState.ACTIVE,
                NOW,
                NOW.plusSeconds(3600),
                0,
                NOW,
                null,
                "POLICY",
                "Operational restriction",
                List.of(new EffectiveRestriction(Scope.USER_BUYING, ActionType.SUSPEND, ACTION_ID)),
                "correlation",
                false);
    }
}
