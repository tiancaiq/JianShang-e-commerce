package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancelRequest;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationResult;
import com.msb.ecom.order_service.model.AdminOrderException;
import com.msb.ecom.order_service.repository.AdminOrderRepository;
import com.msb.ecom.order_service.repository.AdminOrderRepository.AdminCommand;
import com.msb.ecom.order_service.repository.AdminOrderRepository.DetailRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.GroupRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.LockedOrder;
import com.msb.ecom.order_service.repository.AdminOrderRepository.PolicyEvidence;
import com.msb.ecom.order_service.repository.AdminOrderRepository.PolicyRow;
import com.msb.ecom.order_service.security.AdminOrderPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AdminOrderCancellationServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");
    private final AdminOrderService orders = mock(AdminOrderService.class);
    private final AdminOrderRepository repository = mock(AdminOrderRepository.class);
    private final CheckoutUlidGenerator ids = mock(CheckoutUlidGenerator.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AdminOrderCancellationService service;

    @BeforeEach
    void setUp() {
        service = new AdminOrderCancellationService(orders, repository, ids, mapper,
                new ImmediateTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(orders.require(AdminOrderPermission.CANCEL)).thenReturn(context());
    }

    @Test
    void dryRunReadsCurrentStateWithoutPersistingOrCallingCompensationDependencies() {
        DetailRow detail = detail();
        List<GroupRow> groups = List.of(group());
        List<PolicyRow> policies = List.of(policy());
        when(repository.detail(id(1))).thenReturn(java.util.Optional.of(detail));
        when(repository.groups(id(1))).thenReturn(groups);
        when(repository.policies(id(3))).thenReturn(policies);
        when(orders.evaluate(detail, groups, policies, NOW)).thenReturn(new AdminOrderService.Evaluation(true, null, null));

        var preview = service.preview(id(1), request(null));

        assertThat(preview.allowed()).isTrue();
        assertThat(preview.currentOrderVersion()).isEqualTo(4);
        verify(repository).detail(id(1));
        verify(repository).groups(id(1));
        verify(repository).policies(id(3));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void executionUsesLockedVersionWritesTrustedAdminAuditAndCanReplay() throws Exception {
        stubExecution();
        CancellationResult first = service.execute(id(1), request("admin-order-key-1"), "correlation-1");

        assertThat(first.status()).isEqualTo("CANCELLATION_REQUESTED");
        assertThat(first.version()).isEqualTo(5);
        verify(repository).transitionOrder(id(1), 4, NOW);
        verify(repository).insertCancellationRequest(anyString(), any(LockedOrder.class), eq(id(5)),
                eq("FRAUD_PREVENTION"), eq("Risk review"), eq("correlation-1"), anyString(), eq(NOW));
        verify(repository).insertAdminEvent(anyString(), eq(id(1)), eq(id(5)), eq("Admin One"),
                eq("FRAUD_PREVENTION"), eq("Risk review"), eq("correlation-1"), anyString(), eq(NOW));

        String stored = mapper.writeValueAsString(first);
        when(repository.command(id(5), "admin-order-key-1", false)).thenReturn(java.util.Optional.of(
                new AdminCommand(id(20), id(1), requestHash(), "COMPLETED", first.cancellationRequestId(), stored)));
        CancellationResult replay = service.execute(id(1), request("admin-order-key-1"), "correlation-2");
        assertThat(replay.replayed()).isTrue();
        verify(repository, never()).transitionOrder(id(1), 5, NOW);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadConflictsBeforeMutation() {
        when(repository.command(id(5), "admin-order-key-1", false)).thenReturn(java.util.Optional.of(
                new AdminCommand(id(20), id(1), "0".repeat(64), "COMPLETED", id(21), "{}")));

        assertThatThrownBy(() -> service.execute(id(1), request("admin-order-key-1"), "correlation-1"))
                .isInstanceOfSatisfying(AdminOrderException.class,
                        error -> assertThat(error.code()).isEqualTo("ORDER_IDEMPOTENCY_CONFLICT"));
        verify(repository, never()).lockOrder(anyString());
    }

    private void stubExecution() {
        AtomicInteger next = new AtomicInteger(30);
        when(ids.next()).thenAnswer(invocation -> id(next.getAndIncrement()));
        DetailRow detail = detail();
        GroupRow group = group();
        when(repository.command(id(5), "admin-order-key-1", false)).thenReturn(java.util.Optional.empty());
        when(repository.command(id(5), "admin-order-key-1", true)).thenReturn(java.util.Optional.empty());
        when(repository.lockOrder(id(1))).thenReturn(java.util.Optional.of(new LockedOrder(
                id(1), id(3), id(4), id(6), "CONFIRMED", "SUCCEEDED", new BigDecimal("10"), "USD", 4)));
        when(repository.detail(id(1))).thenReturn(java.util.Optional.of(detail));
        when(repository.lockGroups(id(1))).thenReturn(List.of(group));
        when(repository.policies(id(3))).thenReturn(List.of(policy()));
        when(orders.evaluate(eq(detail), any(), any(), eq(NOW)))
                .thenReturn(new AdminOrderService.Evaluation(true, null, null));
        when(repository.policyEvidence(id(3), group)).thenReturn(
                new PolicyEvidence(id(10), "LOCAL_DEMO_CANCELLATION_V1", "BEFORE_FULFILLMENT", 1, 0));
        when(repository.insertCommand(anyString(), eq(id(5)), eq(id(1)), eq("admin-order-key-1"),
                anyString(), eq(4L), eq(NOW), any())).thenReturn(true);
        when(repository.transitionOrder(id(1), 4, NOW)).thenReturn(1);
    }

    private AdminOrderService.RequestContext context() {
        CurrentActor actor = new CurrentActor("subject", "token", "admin@example.test", "Admin One", true);
        return new AdminOrderService.RequestContext(actor, new AdminOrderAuthorizationClient.Access(id(5),
                List.of("SUPER_ADMIN"), List.of(AdminOrderPermission.CANCEL.id()), "ACTIVE"));
    }

    private CancelRequest request(String key) {
        return new CancelRequest("FRAUD_PREVENTION", "Risk review", 4L, key);
    }

    private DetailRow detail() {
        return new DetailRow(id(1), id(2), id(3), id(4), id(6), "CONFIRMED", "SUCCEEDED", "USD",
                new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10"),
                4, NOW, NOW, "COMPLETED", 1, 1, "hash", NOW.plusSeconds(60), id(7), "COMMITTED", 1L,
                "NOT_REQUIRED");
    }

    private GroupRow group() {
        return new GroupRow(id(8), id(9), id(10), "Store", "PENDING_ACCEPTANCE", "NONE", 0,
                NOW.plusSeconds(60));
    }

    private PolicyRow policy() {
        return new PolicyRow(id(9), "LOCAL_DEMO_CANCELLATION_V1", "BEFORE_FULFILLMENT", "Cancel", "Ship", "Return");
    }

    private String requestHash() {
        try {
            String canonical = String.join("\n", id(1), "FRAUD_PREVENTION", "Risk review", "4");
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }

    private static final class ImmediateTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
