package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.BusinessIdentityLabelResponse;
import com.msb.ecom.auth_service.dto.UserIdentityLabelResponse;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOrderIdentityContextServiceTests {
    private static final String USER_ID = id(1);
    private static final String BUSINESS_ID = id(2);
    private final AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
    private final EnforcementService enforcement = mock(EnforcementService.class);
    private final AdminOrderIdentityContextService service =
            new AdminOrderIdentityContextService(authorization, enforcement);

    @Test
    void returnsSafeLabelsAndOnlyActiveIdentityEnforcement() {
        when(authorization.orderIdentityLabels(Set.of(USER_ID), Set.of(BUSINESS_ID))).thenReturn(
                new AdminIdentityLabelsResponse(
                        List.of(new UserIdentityLabelResponse(USER_ID, "Buyer", "buyer", null)),
                        List.of(new BusinessIdentityLabelResponse(BUSINESS_ID, "Store LLC", id(3),
                                "store", "Store", "Irvine", "CA", true))));
        when(enforcement.actions(TargetType.USER, USER_ID)).thenReturn(List.of(
                action(id(4), TargetType.USER, USER_ID, LifecycleState.ACTIVE, Scope.USER_BUYING),
                action(id(5), TargetType.USER, USER_ID, LifecycleState.REVOKED, Scope.USER_LOGIN)));
        when(enforcement.actions(TargetType.BUSINESS, BUSINESS_ID)).thenReturn(List.of(
                action(id(6), TargetType.BUSINESS, BUSINESS_ID, LifecycleState.ACTIVE,
                        Scope.BUSINESS_NEW_SALES)));

        var result = service.resolve(Set.of(USER_ID), Set.of(BUSINESS_ID));

        assertThat(result.users()).extracting(UserIdentityLabelResponse::displayName).containsExactly("Buyer");
        assertThat(result.enforcements()).extracting(value -> value.enforcementActionId())
                .containsExactly(id(4), id(6));
        verify(authorization).orderIdentityLabels(Set.of(USER_ID), Set.of(BUSINESS_ID));
    }

    private Result action(String actionId, TargetType type, String targetId, LifecycleState state, Scope scope) {
        Instant now = Instant.parse("2026-08-16T01:00:00Z");
        return new Result(actionId, type, targetId, ActionType.RESTRICT, Set.of(scope), state,
                now, null, 0, now, state == LifecycleState.REVOKED ? now : null,
                "POLICY", "Reason", List.of(), "correlation", false);
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }
}
