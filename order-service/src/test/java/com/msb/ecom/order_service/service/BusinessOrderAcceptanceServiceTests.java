package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderAcceptanceProperties;
import com.msb.ecom.order_service.dto.BusinessOrderAcceptanceResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceCommand;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessOrderAcceptanceServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final String BUSINESS_ID = id(1);
    private static final String BUSINESS_ORDER_ID = id(2);
    private static final String ORDER_ID = id(3);
    private static final String ACTOR_ID = id(4);
    private static final String COMMAND_ID = id(5);
    private static final String HISTORY_ID = id(6);
    private static final String EVENT_ID = id(7);

    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final BusinessOrderAuthorizationClient authorization =
            mock(BusinessOrderAuthorizationClient.class);
    private final BusinessOrderAcceptanceRepository repository =
            mock(BusinessOrderAcceptanceRepository.class);
    private final CheckoutUlidGenerator ids = mock(CheckoutUlidGenerator.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private BusinessOrderAcceptanceService service;

    @BeforeEach
    void setUp() {
        when(actors.currentActor()).thenReturn(
                new CurrentActor("subject", "actor-token", null, null, true));
        when(authorization.authorizeFulfillment("actor-token", BUSINESS_ID))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(
                        BUSINESS_ID, ACTOR_ID, "OWNER", true));
        when(ids.next()).thenReturn(COMMAND_ID, HISTORY_ID, EVENT_ID);
        when(repository.lockCommand(
                ACTOR_ID, BUSINESS_ID, "ACCEPT_BUSINESS_ORDER", "accept-key-001"))
                .thenReturn(Optional.empty());
        when(repository.lockOwnedGroup(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenReturn(Optional.of(group(0, "PENDING_ACCEPTANCE", "NONE", "SUCCEEDED")));
        when(repository.accept(BUSINESS_ID, BUSINESS_ORDER_ID, 0, NOW)).thenReturn(1);
        service = service(true);
    }

    @Test
    void disabledFailsBeforeValidationActorAuthOrPersistence() {
        BusinessOrderAcceptanceService disabled = service(false);

        assertCode(
                () -> disabled.accept("bad", "bad", null, null, "correlation"),
                "BUSINESS_ORDER_ACCEPTANCE_NOT_AVAILABLE");

        verifyNoInteractions(actors, authorization, repository);
    }

    @Test
    void validatesCanonicalIdsVersionAndIdempotencyBeforeActorResolution() {
        assertCode(
                () -> service.accept("bad", BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_ID_INVALID");
        assertCode(
                () -> service.accept(BUSINESS_ID, BUSINESS_ORDER_ID, null, "accept-key-001", "c"),
                "BUSINESS_ORDER_VERSION_REQUIRED");
        assertCode(
                () -> service.accept(BUSINESS_ID, BUSINESS_ORDER_ID, "W/\"0\"", "accept-key-001", "c"),
                "BUSINESS_ORDER_VERSION_REQUIRED");
        assertCode(
                () -> service.accept(BUSINESS_ID, BUSINESS_ORDER_ID, "\"0\"", "short", "c"),
                "BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED");

        verifyNoInteractions(actors, authorization, repository);
    }

    @Test
    void acceptsQuotedVersionAndWritesOneAtomicCommandHistoryAndOutbox() {
        BusinessOrderAcceptanceResponse response = service.accept(
                BUSINESS_ID,
                BUSINESS_ORDER_ID,
                "\"0\"",
                "accept-key-001",
                "correlation-accept");

        assertThat(response).isEqualTo(new BusinessOrderAcceptanceResponse(
                BUSINESS_ORDER_ID, "ACCEPTED", 1, NOW));
        verify(repository).insertCommand(
                eq(COMMAND_ID),
                eq(ACTOR_ID),
                eq(BUSINESS_ID),
                eq("ACCEPT_BUSINESS_ORDER"),
                eq("accept-key-001"),
                anyString(),
                eq(BUSINESS_ORDER_ID),
                eq(NOW),
                eq(NOW.plusSeconds(7 * 24 * 60 * 60)));
        verify(repository).insertHistory(
                eq(HISTORY_ID), any(), eq(1L), eq(ACTOR_ID),
                eq("correlation-accept"), eq(COMMAND_ID), eq(NOW));
        verify(repository).insertOutbox(
                eq(EVENT_ID),
                eq(BUSINESS_ORDER_ID),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        payload.contains("\"eventType\":\"business_order.accepted\"")
                                && payload.contains("\"businessOrderVersion\":1")
                                && !payload.contains("actor")
                                && !payload.contains("payment")),
                eq("correlation-accept"),
                eq(COMMAND_ID),
                eq(NOW));
        verify(repository).completeCommand(COMMAND_ID, 1, NOW, NOW);
    }

    @Test
    void completedSameHashReplaysAndDifferentHashConflicts() {
        BusinessOrderAcceptanceResponse first = service.accept(
                BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c");
        String requestHash = captureRequestHash();
        when(repository.lockCommand(
                ACTOR_ID, BUSINESS_ID, "ACCEPT_BUSINESS_ORDER", "accept-key-001"))
                .thenReturn(Optional.of(new BusinessOrderAcceptanceCommand(
                        COMMAND_ID, requestHash, "COMPLETED",
                        BUSINESS_ORDER_ID, 1L, NOW)));

        BusinessOrderAcceptanceResponse replay = service.accept(
                BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c");
        assertThat(replay).isEqualTo(first);

        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "1", "accept-key-001", "c"),
                "BUSINESS_ORDER_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void distinguishesVersionStateAndPaymentConflicts() {
        when(repository.lockOwnedGroup(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenReturn(Optional.of(group(1, "PENDING_ACCEPTANCE", "NONE", "SUCCEEDED")));
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_VERSION_CONFLICT");

        when(repository.lockOwnedGroup(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenReturn(Optional.of(group(0, "ACCEPTED", "NONE", "SUCCEEDED")));
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_STATE_CONFLICT");

        when(repository.lockOwnedGroup(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenReturn(Optional.of(group(0, "PENDING_ACCEPTANCE", "NONE", "FAILED")));
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_NOT_PAID");
    }

    @Test
    void authDenialAndDependencyFailureStopBeforePersistence() {
        when(authorization.authorizeFulfillment("actor-token", BUSINESS_ID))
                .thenThrow(new BusinessOrderException(
                        HttpStatus.NOT_FOUND,
                        "BUSINESS_ORDER_NOT_FOUND",
                        "Business order was not found."));
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_NOT_FOUND");
        verifyNoInteractions(repository);
    }

    @Test
    void unexpectedPersistenceFailureUsesBoundedUnavailableContract() {
        doThrow(new RuntimeException("database detail must stay hidden"))
                .when(repository).purgeExpired(any(), anyInt());

        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c"),
                "BUSINESS_ORDER_ACCEPTANCE_UNAVAILABLE");
    }

    private String captureRequestHash() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).insertCommand(
                eq(COMMAND_ID),
                eq(ACTOR_ID),
                eq(BUSINESS_ID),
                eq("ACCEPT_BUSINESS_ORDER"),
                eq("accept-key-001"),
                captor.capture(),
                eq(BUSINESS_ORDER_ID),
                eq(NOW),
                any());
        return captor.getValue();
    }

    private BusinessOrderAcceptanceService service(boolean enabled) {
        return new BusinessOrderAcceptanceService(
                new BusinessOrderAcceptanceProperties(enabled),
                actors,
                authorization,
                repository,
                new BusinessOrderMetrics(meters),
                ids,
                new ObjectMapper().findAndRegisterModules(),
                new TestTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BusinessOrderAcceptanceRepository.GroupState group(
            long version,
            String status,
            String cancellation,
            String payment) {
        return new BusinessOrderAcceptanceRepository.GroupState(
                BUSINESS_ORDER_ID,
                ORDER_ID,
                BUSINESS_ID,
                status,
                cancellation,
                version,
                payment,
                NOW.minusSeconds(60));
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }

    private static final class TestTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
