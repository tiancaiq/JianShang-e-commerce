package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.*;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.repository.AdminFinanceRepository;
import com.msb.ecom.payment_service.repository.AdminFinanceRepository.PaymentRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminFinanceServiceTests {
    private static final String PAYMENT="01K00000000000000000000001",ORDER="01K00000000000000000000002";
    private static final String DISPUTE="01K00000000000000000000003",ADMIN="01K00000000000000000000004";
    private final CurrentActorProvider actors=mock(CurrentActorProvider.class);
    private final AdminFinanceAuthorizationClient authorization=mock(AdminFinanceAuthorizationClient.class);
    private final AdminFinanceOrderClient orders=mock(AdminFinanceOrderClient.class);
    private final AdminFinanceGovernanceClient governance=mock(AdminFinanceGovernanceClient.class);
    private final AdminFinanceRepository repository=mock(AdminFinanceRepository.class);
    private final PaymentProvider provider=mock(PaymentProvider.class);
    private AdminFinanceService service;

    @BeforeEach void setUp(){when(actors.currentActor()).thenReturn(new CurrentActor("subject","token","finance@msb.local","Finance Admin",true));when(authorization.requireAdmin("token")).thenReturn(new AdminFinanceAuthorizationClient.Access(ADMIN,List.of("PLATFORM_ADMIN"),List.of(AdminFinanceService.FINANCE_READ,AdminFinanceService.REFUND_READ,AdminFinanceService.REFUND_EXECUTE),"ACTIVE"));when(provider.providerName()).thenReturn("FAKE_LOCAL_DEMO_V1");when(repository.payment(PAYMENT,false)).thenReturn(java.util.Optional.of(payment()));when(orders.contexts(Set.of(PAYMENT))).thenReturn(Map.of(PAYMENT,context()));service=new AdminFinanceService(actors,authorization,orders,governance,repository,List.of(provider),mock(PaymentUlidGenerator.class),new ObjectMapper(),new NoOpTransactions(),Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));}

    @Test void dryRunCalculatesCurrentImpactWithoutWritingOrCallingProvider(){var preview=service.preview(PAYMENT,new RefundRequest("PARTIAL",new BigDecimal("20.0000"),"USD","DISPUTE_RESOLUTION","Approved recommendation",DISPUTE,4L,null));assertThat(preview.allowed()).isTrue();assertThat(preview.currentlyRefundableAmount()).isEqualByComparingTo("65.0000");assertThat(preview.projectedRefundedTotal()).isEqualByComparingTo("45.0000");assertThat(preview.projectedRemainingRefundableAmount()).isEqualByComparingTo("45.0000");verify(provider,never()).refund(any());verify(repository,never()).insertAdminRefund(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());verify(repository,never()).insertCommand(any(),any(),any(),any(),any(),any(),any());}

    @Test void dryRunRejectsAChangedDisputeAmount(){var preview=service.preview(PAYMENT,new RefundRequest("PARTIAL",new BigDecimal("19.0000"),"USD","DISPUTE_RESOLUTION",null,DISPUTE,4L,null));assertThat(preview.allowed()).isFalse();assertThat(preview.denialReason()).contains("does not match the dispute recommendation");}

    @Test void executePermissionIsRequiredEvenForDryRun(){when(authorization.requireAdmin("token")).thenReturn(new AdminFinanceAuthorizationClient.Access(ADMIN,List.of("AUDITOR"),List.of(AdminFinanceService.FINANCE_READ,AdminFinanceService.REFUND_READ),"ACTIVE"));assertThatThrownBy(()->service.preview(PAYMENT,new RefundRequest("FULL",null,"USD","GOODWILL",null,null,4L,null))).isInstanceOfSatisfying(PaymentIntentException.class,e->assertThat(e.status().value()).isEqualTo(403));verify(repository,never()).payment(any(),anyBoolean());}

    @Test void governedRefundCreatesApprovalWithoutCallingProviderOrWritingPayment(){
        RefundRequest request=new RefundRequest("PARTIAL",new BigDecimal("20.0000"),"USD",
                "DISPUTE_RESOLUTION","Approved recommendation",DISPUTE,4L,"refund-governance-1");
        ApprovalReference approval=new ApprovalReference("01K00000000000000000000009","LARGE_REFUND","HIGH",
                "PAYMENT",PAYMENT,1,0,"PENDING",Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T04:00:00Z"),0);
        when(governance.evaluate(eq("token"),eq(PAYMENT),any(),any(),eq("refund-governance-1")))
                .thenReturn(new RefundApproval("PENDING_APPROVAL",approval,true,"Approval required."));

        GovernedRefundResult result=service.submit(PAYMENT,request,"finance-correlation");

        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.approval().approval().approvalId()).isEqualTo("01K00000000000000000000009");
        verify(provider,never()).refund(any());
        verify(repository,never()).insertCommand(any(),any(),any(),any(),any(),any(),any());
        verify(repository,never()).insertAdminRefund(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    private PaymentRow payment(){Instant at=Instant.parse("2026-08-19T00:00:00Z");return new PaymentRow(PAYMENT,"01K00000000000000000000005","01K00000000000000000000006",new BigDecimal("100.0000"),"USD","FAKE_LOCAL_DEMO_V1","fake_pi_safe","SUCCEEDED",4,at,at,new BigDecimal("100.0000"),new BigDecimal("25.0000"),new BigDecimal("10.0000"),2);}
    private AdminFinanceOrderClient.Context context(){return new AdminFinanceOrderClient.Context(PAYMENT,ORDER,"MSB-1001","01K00000000000000000000006",new BigDecimal("100.0000"),"USD",Instant.parse("2026-08-19T00:00:00Z"),List.of(),List.of(),List.of(new AdminFinanceOrderClient.Dispute(DISPUTE,"01K00000000000000000000007","01K00000000000000000000008","PARTIAL_REFUND_RECOMMENDED",new BigDecimal("20.0000"),"USD","Approved",Instant.parse("2026-08-19T00:00:00Z"))));}
    private static final class NoOpTransactions implements PlatformTransactionManager {public TransactionStatus getTransaction(TransactionDefinition definition){return new SimpleTransactionStatus();}public void commit(TransactionStatus status){}public void rollback(TransactionStatus status){}}
}
