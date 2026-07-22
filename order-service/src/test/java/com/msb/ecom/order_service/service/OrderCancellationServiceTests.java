package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.repository.OrderCancellationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCancellationServiceTests {

    private static final String ORDER_ID = id(1);
    private static final String OTHER_ORDER_ID = id(2);
    private static final String BUYER_ID = id(90);
    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
    private final OrderCancellationRepository repository =
            mock(OrderCancellationRepository.class);
    private final CheckoutUlidGenerator ids = mock(CheckoutUlidGenerator.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    @Test
    void disabledGatePrecedesPathHeadersActorIdentityAndDatabase() {
        OrderCancellationService service = service(false);

        assertCode(
                () -> service.request(
                        "bad", null, null, true, "correlation"),
                "ORDER_CANCELLATION_NOT_AVAILABLE");

        verifyNoInteractions(actors, buyers, repository, ids, transactionManager);
        assertThat(meters.counter(
                "buyer.order.cancellation.requests", "result", "disabled").count())
                .isEqualTo(1);
    }

    @Test
    void enabledValidationUsesStablePrecedenceBeforeActorOrDatabase() {
        OrderCancellationService service = service(true);

        assertCode(
                () -> service.request("bad", null, null, true, "correlation"),
                "ORDER_ID_INVALID");
        assertCode(
                () -> service.request(ORDER_ID, null, null, true, "correlation"),
                "ORDER_CANCELLATION_BODY_NOT_ALLOWED");
        assertCode(
                () -> service.request(ORDER_ID, null, null, false, "correlation"),
                "ORDER_VERSION_REQUIRED");
        assertCode(
                () -> service.request(ORDER_ID, "W/\"0\"", null, false, "correlation"),
                "ORDER_VERSION_REQUIRED");
        assertCode(
                () -> service.request(ORDER_ID, "\"0\"", "short", false, "correlation"),
                "ORDER_CANCELLATION_IDEMPOTENCY_KEY_REQUIRED");

        verifyNoInteractions(actors, buyers, repository, ids, transactionManager);
    }

    @Test
    void propertiesKeepTheApprovedRetentionAndBoundedPurge() {
        assertThatThrownBy(() -> new OrderCancellationProperties(
                true, Duration.ofDays(6), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCancellationProperties(
                true, Duration.ofDays(7), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredKeyIsRemovedBeforeReplayLookupAndNormalOwnershipEvaluation() {
        OrderCancellationService service = transactionalService();
        when(actors.currentActor()).thenReturn(
                new CurrentActor("buyer-subject", "token", null, null, true));
        when(buyers.resolveBuyer("buyer-subject")).thenReturn(BUYER_ID);
        when(repository.deleteExpiredCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-expired",
                NOW)).thenReturn(1);
        when(repository.findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-expired")).thenReturn(Optional.empty());
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.empty());

        assertCode(() -> service.request(
                ORDER_ID, "0", "cancel-key-expired", false, "correlation"),
                "ORDER_NOT_FOUND");

        var ordered = inOrder(repository);
        ordered.verify(repository).deleteExpiredCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-expired",
                NOW);
        ordered.verify(repository).findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-expired");
        ordered.verify(repository).lockOwnedOrder(ORDER_ID, BUYER_ID);
        assertThat(meters.counter(
                "buyer.order.cancellation.requests",
                "result",
                "expired_reclaimed").count()).isEqualTo(1);
    }

    @Test
    void concurrentCrossOrderKeyWinnerMapsToStableIdempotencyConflict() {
        OrderCancellationService service = transactionalService();
        when(actors.currentActor()).thenReturn(
                new CurrentActor("buyer-subject", "token", null, null, true));
        when(buyers.resolveBuyer("buyer-subject")).thenReturn(BUYER_ID);
        when(repository.findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-race")).thenReturn(Optional.empty());
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.of(
                new OrderCancellationRepository.OrderRecord(
                        ORDER_ID, id(10), BUYER_ID, "CONFIRMED", "SUCCEEDED", 0)));
        when(repository.lockBuyerCommandScope(BUYER_ID)).thenReturn(true);
        when(repository.lockCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new OrderCancellationRepository.CommandRecord(
                        id(20),
                        OTHER_ORDER_ID,
                        "f".repeat(64),
                        "COMPLETED",
                        id(21),
                        """
                        {"orderId":"unused"}
                        """,
                        "1")));
        var group = new OrderCancellationRepository.GroupRecord(
                id(30), id(31), "PENDING_ACCEPTANCE", "NONE", NOW.plusSeconds(60));
        when(repository.lockGroups(ORDER_ID)).thenReturn(List.of(group));
        when(repository.findPolicyEvidence(id(10), id(30), id(31))).thenReturn(Optional.of(
                new OrderCancellationRepository.PolicyEvidence(
                        id(40), "TEST_V1", "BEFORE_FULFILLMENT", 1, 0)));
        when(repository.insertCommand(
                any(),
                eq(BUYER_ID),
                eq(ORDER_ID),
                eq(OrderCancellationService.OPERATION),
                eq("cancel-key-race"),
                any(),
                eq(0L),
                eq(NOW),
                eq(NOW.plus(Duration.ofDays(7))))).thenReturn(false);
        when(ids.next()).thenReturn(id(50), id(51), id(52));

        assertCode(() -> service.request(
                ORDER_ID, "0", "cancel-key-race", false, "correlation"),
                "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT");

        verify(repository, times(2)).lockCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-race");
        var ordered = inOrder(repository);
        ordered.verify(repository).lockOwnedOrder(ORDER_ID, BUYER_ID);
        ordered.verify(repository).lockBuyerCommandScope(BUYER_ID);
        ordered.verify(repository).lockCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-race");
    }

    @Test
    void classifiesOnlyDuplicateDeadlockAndLockTimeoutAsIdempotencyRaces() {
        assertThat(OrderCancellationService.isIdempotencyRace(
                new DuplicateKeyException("duplicate"))).isTrue();
        assertThat(OrderCancellationService.isIdempotencyRace(
                new DeadlockLoserDataAccessException("deadlock", null))).isTrue();
        assertThat(OrderCancellationService.isIdempotencyRace(
                new CannotAcquireLockException("lock timeout"))).isTrue();
        assertThat(OrderCancellationService.isIdempotencyRace(
                new IllegalStateException(
                        "wrapped",
                        new CannotAcquireLockException("lock timeout")))).isTrue();
        assertThat(OrderCancellationService.isIdempotencyRace(
                new DataIntegrityViolationException("not a key race"))).isFalse();
    }

    @Test
    void deadlockLoserRereadsDurableWinnerAndReturnsStableHashConflict() {
        OrderCancellationService service = transactionalService();
        prepareActorAndInitialMiss("cancel-key-deadlock");
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.of(
                new OrderCancellationRepository.OrderRecord(
                        ORDER_ID, id(10), BUYER_ID, "CONFIRMED", "SUCCEEDED", 0)));
        when(repository.lockBuyerCommandScope(BUYER_ID))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null));
        when(repository.findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-deadlock")).thenReturn(
                        Optional.empty(),
                        Optional.of(completedCommand(
                                OTHER_ORDER_ID,
                                "f".repeat(64))));

        assertCode(() -> service.request(
                ORDER_ID, "0", "cancel-key-deadlock", false, "correlation"),
                "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT");

        verify(repository, times(2)).findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-deadlock");
    }

    @Test
    void lockTimeoutRereadsDurableWinnerAndReplaysMatchingHash() {
        OrderCancellationService service = transactionalService();
        prepareActorAndInitialMiss("cancel-key-timeout");
        String requestHash = requestHash(ORDER_ID, 0);
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.of(
                new OrderCancellationRepository.OrderRecord(
                        ORDER_ID, id(10), BUYER_ID, "CONFIRMED", "SUCCEEDED", 0)));
        when(repository.lockBuyerCommandScope(BUYER_ID))
                .thenThrow(new CannotAcquireLockException("lock timeout"));
        when(repository.findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-timeout")).thenReturn(
                        Optional.empty(),
                        Optional.of(completedCommand(ORDER_ID, requestHash)));

        var response = service.request(
                ORDER_ID, "0", "cancel-key-timeout", false, "correlation");

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.cancellationRequestId()).isEqualTo(id(21));
        assertThat(meters.counter(
                "buyer.order.cancellation.requests",
                "result",
                "race_recovered").count()).isEqualTo(1);
    }

    @Test
    void classifiedRaceWithoutDurableWinnerRetriesThenFailsUnavailable() {
        OrderCancellationService service = transactionalService();
        prepareActorAndInitialMiss("cancel-key-missing");
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.of(
                new OrderCancellationRepository.OrderRecord(
                        ORDER_ID, id(10), BUYER_ID, "CONFIRMED", "SUCCEEDED", 0)));
        when(repository.lockBuyerCommandScope(BUYER_ID))
                .thenThrow(
                        new CannotAcquireLockException("first timeout"),
                        new CannotAcquireLockException("second timeout"));
        when(repository.findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-missing")).thenReturn(Optional.empty());

        assertCode(() -> service.request(
                ORDER_ID, "0", "cancel-key-missing", false, "correlation"),
                "ORDER_CANCELLATION_UNAVAILABLE");

        verify(repository, times(2)).lockBuyerCommandScope(BUYER_ID);
        verify(repository, times(3)).findCommand(
                BUYER_ID,
                OrderCancellationService.OPERATION,
                "cancel-key-missing");
    }

    @Test
    void unrelatedPersistenceFailureIsNotRetriedAsIdempotencyRace() {
        OrderCancellationService service = transactionalService();
        prepareActorAndInitialMiss("cancel-key-unexpected");
        when(repository.lockOwnedOrder(ORDER_ID, BUYER_ID)).thenReturn(Optional.of(
                new OrderCancellationRepository.OrderRecord(
                        ORDER_ID, id(10), BUYER_ID, "CONFIRMED", "SUCCEEDED", 0)));
        when(repository.lockBuyerCommandScope(BUYER_ID))
                .thenThrow(new DataIntegrityViolationException("unexpected"));

        assertCode(() -> service.request(
                ORDER_ID, "0", "cancel-key-unexpected", false, "correlation"),
                "ORDER_CANCELLATION_UNAVAILABLE");

        verify(repository).lockBuyerCommandScope(BUYER_ID);
        verify(repository).lockOwnedOrder(ORDER_ID, BUYER_ID);
    }

    private void prepareActorAndInitialMiss(String key) {
        when(actors.currentActor()).thenReturn(
                new CurrentActor("buyer-subject", "token", null, null, true));
        when(buyers.resolveBuyer("buyer-subject")).thenReturn(BUYER_ID);
        when(repository.findCommand(BUYER_ID, OrderCancellationService.OPERATION, key))
                .thenReturn(Optional.empty());
    }

    private static OrderCancellationRepository.CommandRecord completedCommand(
            String orderId,
            String requestHash) {
        return new OrderCancellationRepository.CommandRecord(
                id(20),
                orderId,
                requestHash,
                "COMPLETED",
                id(21),
                """
                {
                  "orderId":"%s",
                  "cancellationRequestId":"%s",
                  "status":"CANCELLATION_REQUESTED",
                  "requestStatus":"PENDING",
                  "version":1,
                  "requestedAt":"2026-07-20T02:00:00Z"
                }
                """.formatted(orderId, id(21)),
                "1");
    }

    private static String requestHash(String orderId, long expectedVersion) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (OrderCancellationService.OPERATION + "\n" + orderId + "\n"
                            + expectedVersion).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private OrderCancellationService service(boolean enabled) {
        return new OrderCancellationService(
                new OrderCancellationProperties(enabled, Duration.ofDays(7), 100),
                actors,
                buyers,
                repository,
                ids,
                new OrderCancellationMetrics(meters),
                new ObjectMapper().findAndRegisterModules(),
                transactionManager);
    }

    private OrderCancellationService transactionalService() {
        return new OrderCancellationService(
                new OrderCancellationProperties(true, Duration.ofDays(7), 100),
                actors,
                buyers,
                repository,
                ids,
                new OrderCancellationMetrics(meters),
                new ObjectMapper().findAndRegisterModules(),
                new NoOpTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertCode(Runnable command, String code) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        BuyerOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }

    private static final class NoOpTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
