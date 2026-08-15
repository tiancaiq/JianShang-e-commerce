package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EvaluateCapabilitiesRequest;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessCapabilityServiceTests {
    private static final String BUSINESS_ID="01B00000000000000000000001";
    private static final String ACTION_ID="01E00000000000000000000001";
    private static final Instant NOW=Instant.parse("2026-08-15T01:00:00Z");
    private final EnforcementService enforcement=mock(EnforcementService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BusinessMembershipService memberships=mock(BusinessMembershipService.class);
    private final InternalCommerceAuthenticator internal=mock(InternalCommerceAuthenticator.class);
    private final BusinessCapabilityService service=new BusinessCapabilityService(enforcement,jdbc,memberships,internal);

    @Test void returnsSafeEffectiveBusinessDecisions(){
        when(jdbc.queryForObject(any(String.class),eq(Integer.class),eq(BUSINESS_ID))).thenReturn(1);
        when(enforcement.actions(TargetType.BUSINESS,BUSINESS_ID)).thenReturn(List.of(action()));
        when(enforcement.evaluate(eq(TargetType.BUSINESS),eq(BUSINESS_ID),any())).thenReturn(List.of(new EffectiveRestriction(Scope.BUSINESS_NEW_SALES,ActionType.SUSPEND,ACTION_ID)));
        var response=service.evaluateInternal("service-token",new EvaluateCapabilitiesRequest(BUSINESS_ID,Set.of(Scope.BUSINESS_NEW_SALES,Scope.BUSINESS_LISTING_CREATION)));
        verify(internal).require("service-token");
        assertThat(response.decisions()).filteredOn(d->d.scope()==Scope.BUSINESS_NEW_SALES).singleElement().satisfies(d->{assertThat(d.allowed()).isFalse();assertThat(d.effectiveAction()).isEqualTo(ActionType.SUSPEND);assertThat(d.supportReference()).isEqualTo("ENF-00000001");});
        assertThat(response.decisions()).filteredOn(d->d.scope()==Scope.BUSINESS_LISTING_CREATION).singleElement().extracting(d->d.allowed()).isEqualTo(true);
    }

    @Test void rejectsReservedPayoutEvaluation(){
        assertThatThrownBy(()->service.evaluateInternal("service-token",new EvaluateCapabilitiesRequest(BUSINESS_ID,Set.of(Scope.BUSINESS_PAYOUTS)))).isInstanceOf(EnforcementExceptions.Validation.class);
    }

    private Result action(){return new Result(ACTION_ID,TargetType.BUSINESS,BUSINESS_ID,ActionType.SUSPEND,Set.of(Scope.BUSINESS_NEW_SALES),LifecycleState.ACTIVE,NOW,NOW.plusSeconds(3600),0,NOW,null,"POLICY","Private admin reason",List.of(),"correlation",false);}
}
