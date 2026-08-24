package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.dto.OrderDisputeContracts;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.*;
import com.msb.ecom.order_service.model.OrderDisputeException;
import com.msb.ecom.order_service.repository.OrderDisputeRepository;
import com.msb.ecom.order_service.repository.OrderDisputeRepository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminDisputeServiceTests {
    private final CurrentActorProvider actors=mock(CurrentActorProvider.class);
    private final AdminOrderAuthorizationClient authorization=mock(AdminOrderAuthorizationClient.class);
    private final OrderDisputeRepository repository=mock(OrderDisputeRepository.class);
    private final OrderDisputeService participants=mock(OrderDisputeService.class);
    private final PaymentIntentClient payments=mock(PaymentIntentClient.class);
    private final CheckoutUlidGenerator ids=mock(CheckoutUlidGenerator.class);
    private final Instant now=Instant.parse("2026-08-19T10:00:00Z");
    private AdminDisputeService service;

    @BeforeEach void setUp(){when(actors.currentActor()).thenReturn(new CurrentActor("subject","token","admin@example.com","Admin One",true));
        when(authorization.requireAdmin("token")).thenReturn(new AdminOrderAuthorizationClient.Access(admin(),List.of("PLATFORM_ADMIN"),List.of(AdminDisputeService.READ,AdminDisputeService.ASSIGN,AdminDisputeService.INVESTIGATE,AdminDisputeService.RESOLVE),"ACTIVE"));
        when(ids.next()).thenReturn(id(900),id(901),id(902),id(903),id(904));service=new AdminDisputeService(actors,authorization,repository,participants,payments,ids,Clock.fixed(now, ZoneOffset.UTC));}

    @Test void fullRefundResolutionRecordsRecommendationWithoutPaymentMutation(){DisputeRow ready=row(Status.READY_FOR_DECISION,null,2);DisputeRow resolved=row(Status.REFUND_RECOMMENDED,Resolution.REFUND_RECOMMENDED,3);
        when(repository.find(dispute(),true)).thenReturn(Optional.of(ready));when(repository.find(dispute(),false)).thenReturn(Optional.of(resolved));when(repository.scopeForDispute(dispute(),true)).thenReturn(Optional.of(scope()));when(repository.scopeForDispute(dispute(),false)).thenReturn(Optional.of(scope()));
        when(payments.get(payment(),buyer())).thenReturn(new PaymentIntentClient.PaymentIntent(payment(),id(70),1,buyer(),List.of(business()),new BigDecimal("100.0000"),"USD","FAKE","safe-ref","SUCCEEDED",2,now.plusSeconds(1000),null,null));
        when(repository.command("ADMIN",admin(),"RESOLVE","resolve-key",true)).thenReturn(Optional.empty(),Optional.of(new CommandRow(id(800),"hash",dispute(),"IN_PROGRESS",null)));
        when(repository.insertCommand(anyString(),eq("ADMIN"),eq(admin()),eq("RESOLVE"),eq("resolve-key"),anyString(),eq(dispute()),eq(now))).thenReturn(true);when(repository.resolve(eq(dispute()),eq(2L),eq(admin()),eq(Resolution.REFUND_RECOMMENDED),eq("MERCHANDISE_ISSUE"),anyString(),eq(new BigDecimal("80.0000")),isNull(),isNull(),eq(now))).thenReturn(1);
        when(repository.disputedItems(dispute())).thenReturn(List.of());when(repository.statements(dispute())).thenReturn(List.of());when(repository.evidence(dispute())).thenReturn(List.of());when(repository.notes(dispute())).thenReturn(List.of());when(repository.events(dispute())).thenReturn(List.of());when(participants.items(dispute(),true)).thenReturn(List.of());when(participants.summary(any(),any())).thenReturn(new Summary(dispute(),order(),group(),buyer(),business(),"ITEM_DAMAGED",Priority.MEDIUM,Status.REFUND_RECOMMENDED,admin(),new BigDecimal("100.0000"),"USD",now,now,3));

        service.resolve(dispute(),new ResolveRequest(Resolution.REFUND_RECOMMENDED,"MERCHANDISE_ISSUE","Evidence supports a full remedy.",null,null,null,2L,"resolve-key"),"correlation");

        verify(payments).get(payment(),buyer());verify(repository).resolve(eq(dispute()),eq(2L),eq(admin()),eq(Resolution.REFUND_RECOMMENDED),anyString(),anyString(),eq(new BigDecimal("80.0000")),isNull(),isNull(),eq(now));verifyNoMoreInteractions(payments);
    }

    @Test void invalidPartialAmountIsRejectedBeforeResolution(){when(repository.insertCommand(anyString(),eq("ADMIN"),eq(admin()),eq("RESOLVE"),eq("resolve-key"),anyString(),eq(dispute()),eq(now))).thenReturn(true);when(repository.find(dispute(),true)).thenReturn(Optional.of(row(Status.READY_FOR_DECISION,null,2)));when(repository.scopeForDispute(dispute(),true)).thenReturn(Optional.of(scope()));when(payments.get(payment(),buyer())).thenReturn(new PaymentIntentClient.PaymentIntent(payment(),id(70),1,buyer(),List.of(business()),new BigDecimal("100.0000"),"USD","FAKE","safe-ref","SUCCEEDED",2,now,null,null));
        assertThatThrownBy(()->service.resolve(dispute(),new ResolveRequest(Resolution.PARTIAL_REFUND_RECOMMENDED,"ADJUSTMENT","Bounded adjustment",new BigDecimal("80.0000"),null,null,2L,"resolve-key"),"correlation")).isInstanceOf(OrderDisputeException.class).hasMessageContaining("less than");
        verify(repository,never()).resolve(anyString(),anyLong(),anyString(),any(),anyString(),anyString(),any(),any(),any(),any());
    }

    @Test void completedResolutionRetryReplaysAfterAggregateBecameFinalWithoutReadingPayment(){ResolveRequest request=new ResolveRequest(Resolution.REFUND_RECOMMENDED,"MERCHANDISE_ISSUE","Evidence supports a full remedy.",null,null,null,2L,"resolve-key");
        String hash=OrderDisputeService.hashForAdmin("RESOLVE|"+dispute()+"|REFUND_RECOMMENDED|MERCHANDISE_ISSUE|Evidence supports a full remedy.|null|null|null|2");
        DisputeRow resolved=row(Status.REFUND_RECOMMENDED,Resolution.REFUND_RECOMMENDED,3);when(repository.command("ADMIN",admin(),"RESOLVE","resolve-key",true)).thenReturn(Optional.of(new CommandRow(id(800),hash,dispute(),"COMPLETED",3L)));when(repository.find(dispute(),false)).thenReturn(Optional.of(resolved));when(repository.scopeForDispute(dispute(),false)).thenReturn(Optional.of(scope()));when(repository.disputedItems(dispute())).thenReturn(List.of());when(repository.statements(dispute())).thenReturn(List.of());when(repository.evidence(dispute())).thenReturn(List.of());when(repository.notes(dispute())).thenReturn(List.of());when(repository.events(dispute())).thenReturn(List.of());when(participants.items(dispute(),true)).thenReturn(List.of());when(participants.summary(any(),any())).thenReturn(new Summary(dispute(),order(),group(),buyer(),business(),"ITEM_DAMAGED",Priority.MEDIUM,Status.REFUND_RECOMMENDED,admin(),new BigDecimal("100.0000"),"USD",now,now,3));

        service.resolve(dispute(),request,"correlation");

        verifyNoInteractions(payments);verify(repository,never()).resolve(anyString(),anyLong(),anyString(),any(),anyString(),anyString(),any(),any(),any(),any());verify(repository,never()).find(dispute(),true);
    }

    @Test void assignedToAnotherAdminCannotInvestigate(){DisputeRow other=new DisputeRow(dispute(),order(),group(),business(),buyer(),"BUYER",buyer(),"ITEM_DAMAGED","Damaged",Status.UNDER_ADMIN_REVIEW,Priority.MEDIUM,id(999),null,null,null,null,"USD",null,null,now,now,null,4,"corr");when(repository.find(dispute(),true)).thenReturn(Optional.of(other));
        assertThatThrownBy(()->service.priority(dispute(),new PriorityRequest(Priority.HIGH,4L,"Escalated impact"),"corr")).isInstanceOf(OrderDisputeException.class).hasMessageContaining("assigned");
        verify(repository,never()).priority(anyString(),anyLong(),anyString(),any(),any());
    }

    private DisputeRow row(Status status,Resolution resolution,long version){return new DisputeRow(dispute(),order(),group(),business(),buyer(),"BUYER",buyer(),"ITEM_DAMAGED","Damaged",status,Priority.MEDIUM,admin(),resolution,resolution==null?null:"MERCHANDISE_ISSUE",resolution==null?null:"Resolved",resolution==null?null:new BigDecimal("80.0000"),"USD",null,null,now,now,resolution==null?null:now,version,"corr");}
    private Scope scope(){return new Scope(order(),buyer(),"CONFIRMED","SUCCEEDED",payment(),now.minusSeconds(1000),"USD",group(),business(),"DELIVERED","NONE",new BigDecimal("100.0000"),now,now,4,"COMMITTED","NOT_REQUIRED","LOCAL_DEMO_MANUAL","Parcel Co","Ground","SAFE-TRACKING","DELIVERED",now.minusSeconds(200),now.minusSeconds(100),new BigDecimal("20.0000"));}
    private static String id(int n){return "01"+String.format("%024d",n);}private static String dispute(){return id(1);}private static String order(){return id(2);}private static String group(){return id(3);}private static String business(){return id(4);}private static String buyer(){return id(5);}private static String admin(){return id(6);}private static String payment(){return id(7);}
}
