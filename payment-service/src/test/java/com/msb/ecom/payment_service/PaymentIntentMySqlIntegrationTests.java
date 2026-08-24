package com.msb.ecom.payment_service;

import com.msb.ecom.payment_service.dto.CreatePaymentIntentRequest;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundExecution;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundRequest;
import com.msb.ecom.payment_service.dto.PaymentIntentResponse;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.dto.CreatePaymentRefundRequest;
import com.msb.ecom.payment_service.dto.PaymentRefundResponse;
import com.msb.ecom.payment_service.dto.CreateReturnRefundRequest;
import com.msb.ecom.payment_service.dto.ReturnRefundResponse;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.repository.AdminFinanceRepository;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.service.AdminFinanceAuthorizationClient;
import com.msb.ecom.payment_service.service.AdminFinanceGovernanceClient;
import com.msb.ecom.payment_service.service.AdminFinanceOrderClient;
import com.msb.ecom.payment_service.service.AdminFinanceService;
import com.msb.ecom.payment_service.service.PaymentUlidGenerator;
import com.msb.ecom.payment_service.service.PaymentIntentService;
import com.msb.ecom.payment_service.service.PaymentWebhookService;
import com.msb.ecom.payment_service.service.PaymentRefundService;
import com.msb.ecom.payment_service.service.PaymentReturnRefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.flyway.out-of-order=false",
        "payment.intents.enabled=true",
        "payment.internal-service-token=payment-test-token",
        "payment.webhooks.enabled=true",
        "payment.webhooks.fake.secret=payment-webhook-test-secret",
        "payment.refunds.enabled=true"
})
class PaymentIntentMySqlIntegrationTests {

    private static final String TOKEN = "payment-test-token";
    private static final String WEBHOOK_SECRET = "payment-webhook-test-secret";

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @Autowired
    private PaymentIntentService service;

    @Autowired
    private PaymentWebhookService webhookService;

    @Autowired
    private PaymentRefundService refundService;

    @Autowired
    private PaymentReturnRefundService returnRefundService;

    @Autowired
    private PaymentIntentRepository repository;

    @Autowired
    private AdminFinanceRepository adminFinanceRepository;

    @Autowired
    private List<PaymentProvider> paymentProviders;

    @Autowired
    private PaymentUlidGenerator paymentIds;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_outbox_events");
        jdbc.update("DELETE FROM payment_admin_refund_commands");
        jdbc.update("DELETE FROM payment_return_refund_attempts");
        jdbc.update("DELETE FROM payment_return_refunds");
        jdbc.update("DELETE FROM payment_provider_events");
        jdbc.update("DELETE FROM payment_refund_attempts");
        jdbc.update("DELETE FROM payment_refund_status_history");
        jdbc.update("DELETE FROM payment_refunds");
        jdbc.update("DELETE FROM payment_idempotency_records");
        jdbc.update("DELETE FROM payment_status_history");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payment_intent_business_scopes");
        jdbc.update("DELETE FROM payment_intents");
    }

    @Test
    void persistsAuthoritativeSnapshotScopesHistoryAndSafeProviderMetadata() {
        PaymentIntentResponse response = service.create(
                TOKEN,
                "payment-key-0001",
                request(id(1), id(2), List.of(id(4), id(3)), "42.2500"),
                "correlation-create");

        assertThat(response.checkoutId()).isEqualTo(id(1));
        assertThat(response.checkoutVersion()).isEqualTo(7);
        assertThat(response.buyerId()).isEqualTo(id(2));
        assertThat(response.businessIds()).containsExactly(id(3), id(4));
        assertThat(response.amount()).isEqualByComparingTo("42.2500");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("REQUIRES_ACTION");
        assertThat(response.provider()).isEqualTo("FAKE_LOCAL_DEMO_V1");
        assertThat(response.providerReference()).startsWith("fake_pi_");
        assertThat(response.providerAction().type()).isEqualTo("FAKE_HOSTED_ACTION");
        assertThat(response.providerAction().reference()).startsWith("fake_action_");
        assertThat(response.error()).isNull();
        assertThat(repository.historyStatuses(response.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION");
        assertThat(repository.attemptCount(response.id())).isEqualTo(1);

        var row = jdbc.queryForMap("""
                        SELECT checkout_snapshot_hash, caller_scope, payment_method_type,
                               capture_method, merchant_of_record, funds_flow, safe_error_message
                        FROM payment_intents WHERE id = ?
                        """,
                response.id());
        assertThat(row.get("checkout_snapshot_hash")).isEqualTo("a".repeat(64));
        assertThat(row.get("caller_scope")).isEqualTo("ORDER_SERVICE");
        assertThat(row.get("payment_method_type")).isEqualTo("CARD");
        assertThat(row.get("capture_method")).isEqualTo("AUTOMATIC");
        assertThat(row.get("merchant_of_record")).isEqualTo("PLATFORM");
        assertThat(row.get("funds_flow")).isEqualTo("SEPARATE_CHARGE_TRANSFER");
        assertThat(row.get("safe_error_message")).isNull();
    }

    @Test
    void replaysSameRequestAndRejectsPayloadHashOrCheckoutConflicts() {
        CreatePaymentIntentRequest request = request(id(10), id(11), List.of(id(12)), "19.9900");
        PaymentIntentResponse first = service.create(
                TOKEN, "payment-key-0010", request, "correlation-1");
        PaymentIntentResponse replay = service.create(
                TOKEN, "payment-key-0010", request, "correlation-2");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.providerReference()).isEqualTo(first.providerReference());
        assertThat(repository.attemptCount(first.id())).isEqualTo(1);

        assertThatThrownBy(() -> service.create(
                TOKEN,
                "payment-key-0010",
                request(id(10), id(11), List.of(id(12)), "20.0000"),
                "correlation-3"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("PAYMENT_IDEMPOTENCY_CONFLICT");
                });

        assertThatThrownBy(() -> service.create(
                TOKEN,
                "payment-key-other",
                request,
                "correlation-4"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("PAYMENT_INTENT_ALREADY_EXISTS");
                });
    }

    @Test
    void enforcesInternalAuthenticationAndBuyerIsolation() {
        PaymentIntentResponse created = service.create(
                TOKEN,
                "payment-key-0020",
                request(id(20), id(21), List.of(id(22)), "8.0000"),
                "correlation-auth");

        assertThatThrownBy(() -> service.get("wrong-token", created.id(), id(21)))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("PAYMENT_INTERNAL_AUTH_REQUIRED");
                });
        assertThatThrownBy(() -> service.get(TOKEN, created.id(), id(23)))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("PAYMENT_INTENT_NOT_FOUND");
                });
        assertThat(service.get(TOKEN, created.id(), id(21)).businessIds())
                .containsExactly(id(22));
    }

    @Test
    void internalHttpContractRequiresServiceAuthenticationWithoutLeakingCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/internal/payment-intents")
                        .header("Idempotency-Key", "payment-key-http")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(
                                request(id(24), id(25), List.of(id(26)), "9.0000"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_INTERNAL_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message")
                        .value("Internal payment service authentication is required."));
    }

    @Test
    void persistsBoundedProviderFailureMetadataAndImmutableHistoryEntry() {
        PaymentIntentResponse created = service.create(
                TOKEN,
                "payment-key-0027",
                request(id(27), id(28), List.of(id(29)), "11.0000"),
                "correlation-provider-failure");
        PaymentIntent current = repository.find(created.id()).orElseThrow();
        Instant now = Instant.now();
        PaymentIntent failed = current.transition(
                PaymentIntentStatus.FAILED,
                current.providerReference(),
                null,
                "PROVIDER_DECLINED",
                "Payment method was declined.",
                now);

        Integer changed = transactions.execute(status -> repository.transition(
                current,
                failed,
                id(43),
                "PROVIDER_STATUS_FAILED",
                "correlation-provider-failure"));

        assertThat(changed).isEqualTo(1);
        PaymentIntent persisted = repository.find(created.id()).orElseThrow();
        assertThat(persisted.safeErrorCode()).isEqualTo("PROVIDER_DECLINED");
        assertThat(persisted.safeErrorMessage()).isEqualTo("Payment method was declined.");
        assertThat(repository.historyStatuses(created.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION", "FAILED");
    }

    @Test
    void optimisticVersionAllowsOnlyOneConcurrentStateWriter() throws Exception {
        PaymentIntentResponse created = service.create(
                TOKEN,
                "payment-key-0030",
                request(id(30), id(31), List.of(id(32)), "5.0000"),
                "correlation-version");
        PaymentIntent current = repository.find(created.id()).orElseThrow();
        Instant now = Instant.now();
        PaymentIntent next = current.transition(
                PaymentIntentStatus.PROCESSING,
                current.providerReference(),
                current.providerActionType(),
                null,
                null,
                now);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transitionAfter(start, current, next, id(41)));
            var second = executor.submit(() -> transitionAfter(start, current, next, id(42)));
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }

        PaymentIntent persisted = repository.find(created.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(persisted.version()).isEqualTo(2);
        assertThat(repository.historyStatuses(created.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION", "PROCESSING");
    }

    @Test
    void concurrentIdempotentCreatesConvergeOnOneIntentAndOneProviderAttempt() throws Exception {
        CreatePaymentIntentRequest request = request(
                id(50), id(51), List.of(id(52), id(53)), "55.0000");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfter(start, request));
            var second = executor.submit(() -> createAfter(start, request));
            start.countDown();
            PaymentIntentResponse firstResult = first.get(10, TimeUnit.SECONDS);
            PaymentIntentResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(secondResult.providerReference()).isEqualTo(firstResult.providerReference());
            assertThat(repository.attemptCount(firstResult.id())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_idempotency_records", Integer.class)).isEqualTo(1);
    }

    @Test
    void verifiedSuccessAtomicallyRecordsProviderEventHistoryAttemptAndOutbox() {
        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-0060",
                request(id(60), id(61), List.of(id(62), id(63)), "64.5000"),
                "correlation-webhook-success");
        byte[] body = webhookBody(
                "fake_evt_success_0001",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);

        PaymentWebhookResponse response = webhookService.process(
                signature(body),
                body,
                "correlation-webhook-success");

        assertThat(response.paymentIntentId()).isEqualTo(intent.id());
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.outcome()).isEqualTo("APPLIED");
        assertThat(response.replayed()).isFalse();
        assertThat(repository.historyStatuses(intent.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION", "SUCCEEDED");
        assertThat(repository.attemptCount(intent.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_provider_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_events", Integer.class)).isEqualTo(1);

        var event = jdbc.queryForMap("""
                SELECT processing_outcome, resulting_status, payload_hash, correlation_id
                FROM payment_provider_events WHERE provider_event_id = ?
                """, "fake_evt_success_0001");
        assertThat(event.get("processing_outcome")).isEqualTo("APPLIED");
        assertThat(event.get("resulting_status")).isEqualTo("SUCCEEDED");
        assertThat(event.get("payload_hash").toString()).hasSize(64);
        assertThat(event.get("correlation_id")).isEqualTo("correlation-webhook-success");
        assertThat(jdbc.queryForObject("""
                SELECT actor_scope FROM payment_status_history
                WHERE payment_intent_id = ? AND to_status = 'SUCCEEDED'
                """, String.class, intent.id())).isEqualTo("FAKE_PROVIDER");

        var outbox = jdbc.queryForMap("""
                SELECT event_type, event_version, producer, payload_json, published_at, retry_count
                FROM payment_outbox_events WHERE aggregate_id = ?
                """, intent.id());
        assertThat(outbox.get("event_type")).isEqualTo("payment.succeeded");
        assertThat(outbox.get("event_version")).isEqualTo(1L);
        assertThat(outbox.get("producer")).isEqualTo("payment-service");
        assertThat(outbox.get("published_at")).isNull();
        assertThat(outbox.get("retry_count")).isEqualTo(0L);
        assertThat(outbox.get("payload_json").toString())
                .contains(intent.id(), intent.checkoutId(), "64.5000", "USD")
                .doesNotContain(intent.buyerId(), intent.providerReference(), "CARD");
    }

    @Test
    void providerEventReplayIsSafeAndDifferentPayloadReuseConflicts() {
        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-0070",
                request(id(70), id(71), List.of(id(72)), "7.0000"),
                "correlation-webhook-replay");
        byte[] success = webhookBody(
                "fake_evt_replay_0001",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);

        PaymentWebhookResponse first = webhookService.process(
                signature(success), success, "correlation-webhook-replay-1");
        PaymentWebhookResponse replay = webhookService.process(
                signature(success), success, "correlation-webhook-replay-2");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentIntentId()).isEqualTo(first.paymentIntentId());
        assertThat(repository.attemptCount(intent.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_provider_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_events", Integer.class)).isEqualTo(1);

        byte[] conflict = webhookBody(
                "fake_evt_replay_0001",
                "payment_intent.failed",
                intent.providerReference(),
                "CARD_DECLINED");
        assertThatThrownBy(() -> webhookService.process(
                signature(conflict), conflict, "correlation-webhook-conflict"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOK_EVENT_CONFLICT");
                });
    }

    @Test
    void unknownIllegalAndLateEventsAreExplicitAndNeverDuplicateTerminalOutbox() {
        byte[] unknownBody = webhookBody(
                "fake_evt_unknown_0001",
                "payment_intent.succeeded",
                "fake_pi_" + id(79).toLowerCase(),
                null);
        PaymentWebhookResponse unknown = webhookService.process(
                signature(unknownBody), unknownBody, "correlation-unknown");
        assertThat(unknown.outcome()).isEqualTo("UNKNOWN_INTENT");
        assertThat(unknown.paymentIntentId()).isNull();

        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-0080",
                request(id(80), id(81), List.of(id(82)), "18.0000"),
                "correlation-terminal");
        byte[] appliedBody = webhookBody(
                "fake_evt_terminal_0001",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);
        webhookService.process(signature(appliedBody), appliedBody, "correlation-terminal-1");

        byte[] lateBody = webhookBody(
                "fake_evt_terminal_0002",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);
        PaymentWebhookResponse late = webhookService.process(
                signature(lateBody), lateBody, "correlation-terminal-2");
        assertThat(late.outcome()).isEqualTo("IGNORED_LATE_EVENT");
        assertThat(late.status()).isEqualTo("SUCCEEDED");

        byte[] illegalBody = webhookBody(
                "fake_evt_terminal_0003",
                "payment_intent.failed",
                intent.providerReference(),
                "CARD_DECLINED");
        PaymentWebhookResponse illegal = webhookService.process(
                signature(illegalBody), illegalBody, "correlation-terminal-3");
        assertThat(illegal.outcome()).isEqualTo("REJECTED_ILLEGAL_TRANSITION");
        assertThat(illegal.status()).isEqualTo("SUCCEEDED");

        assertThat(repository.historyStatuses(intent.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION", "SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_events WHERE aggregate_id = ?",
                Integer.class,
                intent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_provider_events", Integer.class)).isEqualTo(4);
    }

    @Test
    void processingIntentAcceptsVerifiedFailureWithSafeMappedError() {
        PaymentIntentResponse created = service.create(
                TOKEN,
                "payment-key-0090",
                request(id(90), id(91), List.of(id(92)), "20.0000"),
                "correlation-processing");
        PaymentIntent current = repository.find(created.id()).orElseThrow();
        PaymentIntent processing = current.transition(
                PaymentIntentStatus.PROCESSING,
                current.providerReference(),
                current.providerActionType(),
                null,
                null,
                Instant.now());
        transactions.executeWithoutResult(status -> repository.transition(
                current,
                processing,
                id(93),
                "PROVIDER_PROCESSING",
                "correlation-processing"));
        byte[] body = webhookBody(
                "fake_evt_failure_0001",
                "payment_intent.failed",
                created.providerReference(),
                "UNRECOGNIZED_PROVIDER_DETAIL");

        PaymentWebhookResponse response = webhookService.process(
                signature(body), body, "correlation-processing-failed");
        PaymentIntent persisted = repository.find(created.id()).orElseThrow();

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.outcome()).isEqualTo("APPLIED");
        assertThat(persisted.safeErrorCode()).isEqualTo("PAYMENT_FAILED");
        assertThat(persisted.safeErrorMessage()).isEqualTo("Payment failed.");
        assertThat(jdbc.queryForObject("""
                SELECT event_type FROM payment_outbox_events WHERE aggregate_id = ?
                """, String.class, created.id())).isEqualTo("payment.failed");
    }

    @Test
    void verifiedTerminalEventCannotSkipCreatedIntentState() {
        Instant now = Instant.now();
        String paymentIntentId = id(94);
        String providerReference = "fake_pi_" + paymentIntentId.toLowerCase();
        PaymentIntent created = new PaymentIntent(
                paymentIntentId,
                id(95),
                1,
                "b".repeat(64),
                id(96),
                "ORDER_SERVICE",
                List.of(id(97)),
                new BigDecimal("12.0000"),
                "USD",
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                "FAKE_LOCAL_DEMO_V1",
                providerReference,
                null,
                PaymentIntentStatus.CREATED,
                0,
                now.plusSeconds(900),
                null,
                null,
                now,
                now);
        transactions.executeWithoutResult(status ->
                repository.insert(created, id(98), "correlation-created-state"));
        byte[] body = webhookBody(
                "fake_evt_created_0001",
                "payment_intent.succeeded",
                providerReference,
                null);

        PaymentWebhookResponse response = webhookService.process(
                signature(body), body, "correlation-created-state");

        assertThat(response.outcome()).isEqualTo("REJECTED_ILLEGAL_TRANSITION");
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(repository.find(paymentIntentId).orElseThrow().status())
                .isEqualTo(PaymentIntentStatus.CREATED);
        assertThat(repository.historyStatuses(paymentIntentId)).containsExactly("CREATED");
        assertThat(repository.attemptCount(paymentIntentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_events", Integer.class)).isZero();
    }

    @Test
    void concurrentDuplicateCallbacksApplyExactlyOnce() throws Exception {
        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-0100",
                request(id(100), id(101), List.of(id(102)), "25.0000"),
                "correlation-webhook-concurrent");
        byte[] body = webhookBody(
                "fake_evt_concurrent_0001",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);
        String signature = signature(body);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> webhookAfter(start, signature, body));
            var second = executor.submit(() -> webhookAfter(start, signature, body));
            start.countDown();
            PaymentWebhookResponse firstResult = first.get(10, TimeUnit.SECONDS);
            PaymentWebhookResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_provider_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_events", Integer.class)).isEqualTo(1);
        assertThat(repository.historyStatuses(intent.id()))
                .containsExactly("CREATED", "REQUIRES_ACTION", "SUCCEEDED");
        assertThat(repository.attemptCount(intent.id())).isEqualTo(2);
    }

    @Test
    void httpWebhookUsesProviderSignatureWithoutBrowserOrServiceActor() throws Exception {
        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-0110",
                request(id(110), id(111), List.of(id(112)), "30.0000"),
                "correlation-webhook-http");
        byte[] body = webhookBody(
                "fake_evt_http_0001",
                "payment_intent.succeeded",
                intent.providerReference(),
                null);

        mockMvc.perform(post("/api/v1/webhooks/payments")
                        .header("X-MSB-Signature", signature(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentIntentId").value(intent.id()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.outcome").value("APPLIED"));

        mockMvc.perform(post("/api/v1/webhooks/payments")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WEBHOOK_SIGNATURE_INVALID"));

        byte[] invalidSchema = "{}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/v1/webhooks/payments")
                        .header("X-MSB-Signature", signature(invalidSchema))
                        .contentType("application/json")
                        .content(invalidSchema))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WEBHOOK_PAYLOAD_INVALID"));
    }

    @Test
    void deterministicFullRefundDerivesAmountAndReplaysExactlyOnce() {
        PaymentIntentResponse intent = service.create(
                TOKEN,
                "payment-key-refund-0120",
                request(id(120), id(121), List.of(id(122), id(123)), "48.7500"),
                "correlation-refund-create");
        byte[] succeeded = webhookBody(
                "fake_evt_refund_0001", "payment_intent.succeeded",
                intent.providerReference(), null);
        webhookService.process(signature(succeeded), succeeded, "correlation-refund-succeeded");
        CreatePaymentRefundRequest command = new CreatePaymentRefundRequest(id(124), id(125));

        PaymentRefundResponse first = refundService.refund(
                TOKEN, intent.id(), "refund-key-0120", command, "correlation-refund-1");
        PaymentRefundResponse replay = refundService.refund(
                TOKEN, intent.id(), "refund-key-0120", command, "correlation-refund-2");

        assertThat(replay.refundId()).isEqualTo(first.refundId());
        assertThat(first.amount()).isEqualByComparingTo("48.7500");
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.displayName()).isEqualTo("Local demo refund");
        assertThat(first.disclosure()).isEqualTo("No real money is moved.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refunds", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_attempts", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_status_history", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                WHERE event_type = 'payment.refunded'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void deterministicBusinessGroupReturnRefundIsBoundedAndReplaysExactlyOnce() {
        PaymentIntentResponse intent = service.create(
                TOKEN, "payment-key-return-0130",
                request(id(130), id(131), List.of(id(132), id(133)), "48.7500"),
                "correlation-return-create");
        byte[] succeeded = webhookBody("fake_evt_return_0001", "payment_intent.succeeded",
                intent.providerReference(), null);
        webhookService.process(signature(succeeded), succeeded, "correlation-return-succeeded");
        CreateReturnRefundRequest command = new CreateReturnRefundRequest(
                id(130), id(135), id(134), new BigDecimal("20.2500"), "USD");

        ReturnRefundResponse first = returnRefundService.refund(
                TOKEN, intent.id(), "return-refund-key-0130", command, "correlation-return-1");
        ReturnRefundResponse replay = returnRefundService.refund(
                TOKEN, intent.id(), "return-refund-key-0130", command, "correlation-return-2");

        assertThat(replay.refundId()).isEqualTo(first.refundId());
        assertThat(first.amount()).isEqualByComparingTo("20.2500");
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.disclosure()).isEqualTo("No real money is moved.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_return_refunds", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_return_refund_attempts", Integer.class))
                .isEqualTo(1);

        CreateReturnRefundRequest excessive = new CreateReturnRefundRequest(
                id(130), id(137), id(136), new BigDecimal("30.0000"), "USD");
        assertThatThrownBy(() -> returnRefundService.refund(
                TOKEN, intent.id(), "return-refund-key-0131", excessive, "correlation-return-3"))
                .isInstanceOfSatisfying(PaymentIntentException.class,
                        error -> assertThat(error.code()).isEqualTo("PAYMENT_RETURN_REFUND_STATE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_return_refunds", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refundCeilingIsSharedAcrossAdminReturnAndCancellationSources() {
        PaymentIntentResponse firstIntent = service.create(TOKEN, "payment-key-shared-refund-0140",
                request(id(140), id(141), List.of(id(142)), "50.0000"), "correlation-shared-create-1");
        byte[] firstSucceeded = webhookBody("fake_evt_shared_refund_0140", "payment_intent.succeeded",
                firstIntent.providerReference(), null);
        webhookService.process(signature(firstSucceeded), firstSucceeded, "correlation-shared-success-1");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO payment_refunds (
                  id,payment_intent_id,cancellation_request_id,order_id,dispute_id,source,refund_type,
                  reason_code,reason,initiated_by_admin_id,initiated_by_admin_name,idempotency_key,
                  amount,currency,provider,provider_reference,status,version,reconciliation_state,
                  safe_failure_code,safe_failure_summary,created_at,updated_at,completed_at)
                VALUES (?,?,NULL,?,NULL,'HUMAN_ADMIN','PARTIAL','CUSTOMER_REMEDIATION','Test boundary',
                  ?, 'Finance Admin', ?, 30.0000,'USD','FAKE_LOCAL_DEMO_V1',?,'SUCCEEDED',0,'IN_SYNC',
                  NULL,NULL,?,?,?)
                """, id(143), firstIntent.id(), id(144), id(145), "admin-refund-boundary-0140",
                "fake_refund_boundary_0140", now, now, now);
        CreateReturnRefundRequest excessiveReturn = new CreateReturnRefundRequest(
                id(144), id(147), id(146), new BigDecimal("21.0000"), "USD");
        assertThatThrownBy(() -> returnRefundService.refund(TOKEN, firstIntent.id(),
                "return-after-admin-0140", excessiveReturn, "correlation-shared-return"))
                .isInstanceOfSatisfying(PaymentIntentException.class,
                        error -> assertThat(error.code()).isEqualTo("PAYMENT_RETURN_REFUND_STATE_CONFLICT"));

        PaymentIntentResponse secondIntent = service.create(TOKEN, "payment-key-shared-refund-0150",
                request(id(150), id(151), List.of(id(152)), "50.0000"), "correlation-shared-create-2");
        byte[] secondSucceeded = webhookBody("fake_evt_shared_refund_0150", "payment_intent.succeeded",
                secondIntent.providerReference(), null);
        webhookService.process(signature(secondSucceeded), secondSucceeded, "correlation-shared-success-2");
        returnRefundService.refund(TOKEN, secondIntent.id(), "return-before-cancel-0150",
                new CreateReturnRefundRequest(id(154), id(155), id(153), new BigDecimal("10.0000"), "USD"),
                "correlation-shared-return-2");
        assertThatThrownBy(() -> refundService.refund(TOKEN, secondIntent.id(), "cancel-after-return-0150",
                new CreatePaymentRefundRequest(id(154), id(156)), "correlation-shared-cancel"))
                .isInstanceOfSatisfying(PaymentIntentException.class,
                        error -> assertThat(error.code()).isEqualTo("PAYMENT_REFUND_STATE_CONFLICT"));
    }

    @Test
    void concurrentAdminAndReturnRefundsCannotExceedTheSharedPaymentCeiling() throws Exception {
        PaymentIntentResponse intent = service.create(TOKEN, "payment-key-concurrent-refund-0160",
                request(id(160), id(161), List.of(id(162)), "50.0000"),
                "correlation-concurrent-refund-create");
        byte[] succeeded = webhookBody("fake_evt_concurrent_refund_0160", "payment_intent.succeeded",
                intent.providerReference(), null);
        webhookService.process(signature(succeeded), succeeded, "correlation-concurrent-refund-success");

        String orderId = id(164);
        long paymentVersion = adminFinanceRepository.payment(intent.id(), false).orElseThrow().version();
        AdminFinanceService adminFinance = adminFinance(intent.id(), orderId);
        RefundRequest adminRequest = new RefundRequest(
                "PARTIAL", new BigDecimal("30.0000"), "USD", "CUSTOMER_REMEDIATION",
                "Concurrent ceiling test", null, paymentVersion, "admin-concurrent-refund-0160");
        CreateReturnRefundRequest returnRequest = new CreateReturnRefundRequest(
                orderId, id(165), id(163), new BigDecimal("30.0000"), "USD");

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var admin = executor.submit(() -> adminRefundOutcomeAfter(
                    start, adminFinance, intent.id(), adminRequest));
            var returned = executor.submit(() -> returnRefundOutcomeAfter(
                    start, intent.id(), returnRequest));
            start.countDown();

            List<ConcurrentRefundOutcome> outcomes = List.of(
                    admin.get(20, TimeUnit.SECONDS),
                    returned.get(20, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(ConcurrentRefundOutcome::succeeded).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded()).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                    .extracting(ConcurrentRefundOutcome::code)
                    .allMatch(code -> code != null && !code.isBlank());
        } finally {
            executor.shutdownNow();
        }

        BigDecimal adminRefunded = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM payment_refunds
                WHERE payment_intent_id = ? AND status = 'SUCCEEDED'
                """, BigDecimal.class, intent.id());
        BigDecimal returnRefunded = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM payment_return_refunds
                WHERE payment_intent_id = ? AND status = 'SUCCEEDED'
                """, BigDecimal.class, intent.id());
        assertThat(adminRefunded.add(returnRefunded)).isEqualByComparingTo("30.0000");
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM payment_refunds WHERE payment_intent_id = ?)
                     + (SELECT COUNT(*) FROM payment_return_refunds WHERE payment_intent_id = ?)
                """, Integer.class, intent.id(), intent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM payment_refund_attempts a
                          JOIN payment_refunds r ON r.id = a.refund_id
                         WHERE r.payment_intent_id = ?)
                     + (SELECT COUNT(*) FROM payment_return_refund_attempts a
                          JOIN payment_return_refunds r ON r.id = a.refund_id
                         WHERE r.payment_intent_id = ?)
                """, Integer.class, intent.id(), intent.id())).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyAdminRefundsExecuteOnceAndReplayTheStoredResult() throws Exception {
        PaymentIntentResponse intent = service.create(TOKEN, "payment-key-admin-replay-0170",
                request(id(170), id(171), List.of(id(172)), "50.0000"),
                "correlation-admin-replay-create");
        byte[] succeeded = webhookBody("fake_evt_admin_replay_0170", "payment_intent.succeeded",
                intent.providerReference(), null);
        webhookService.process(signature(succeeded), succeeded, "correlation-admin-replay-success");

        String orderId = id(174);
        long paymentVersion = adminFinanceRepository.payment(intent.id(), false).orElseThrow().version();
        AdminFinanceService adminFinance = adminFinance(intent.id(), orderId);
        RefundRequest request = new RefundRequest(
                "PARTIAL", new BigDecimal("10.0000"), "USD", "CUSTOMER_REMEDIATION",
                "Concurrent idempotency test", null, paymentVersion, "admin-same-key-refund-0170");

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> adminRefundAfter(
                    start, adminFinance, intent.id(), request, "correlation-admin-replay-1"));
            var second = executor.submit(() -> adminRefundAfter(
                    start, adminFinance, intent.id(), request, "correlation-admin-replay-2"));
            start.countDown();

            RefundExecution firstResult = first.get(20, TimeUnit.SECONDS);
            RefundExecution secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult.refundId()).isEqualTo(firstResult.refundId());
            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE payment_intent_id = ?",
                Integer.class, intent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM payment_refund_attempts a
                JOIN payment_refunds r ON r.id = a.refund_id
                WHERE r.payment_intent_id = ?
                """,
                Integer.class, intent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_admin_refund_commands WHERE payment_intent_id = ?",
                Integer.class, intent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                WHERE aggregate_id = ? AND event_type = 'payment.refunded'
                """, Integer.class, intent.id())).isEqualTo(1);
    }

    private int transitionAfter(
            CountDownLatch start,
            PaymentIntent current,
            PaymentIntent next,
            String historyId) throws InterruptedException {
        start.await();
        Integer changed = transactions.execute(status -> repository.transition(
                current,
                next,
                historyId,
                "TEST_CONCURRENT_TRANSITION",
                "correlation-concurrency"));
        return changed == null ? 0 : changed;
    }

    private PaymentIntentResponse createAfter(
            CountDownLatch start,
            CreatePaymentIntentRequest request) throws InterruptedException {
        start.await();
        return service.create(
                TOKEN,
                "payment-key-0050",
                request,
                "correlation-idempotency");
    }

    private PaymentWebhookResponse webhookAfter(
            CountDownLatch start,
            String signature,
            byte[] body) throws InterruptedException {
        start.await();
        return webhookService.process(
                signature,
                body,
                "correlation-webhook-concurrent");
    }

    private RefundExecution adminRefundAfter(
            CountDownLatch start,
            AdminFinanceService adminFinance,
            String paymentId,
            RefundRequest request,
            String correlation) throws InterruptedException {
        start.await();
        return adminFinance.execute(paymentId, request, correlation);
    }

    private ConcurrentRefundOutcome adminRefundOutcomeAfter(
            CountDownLatch start,
            AdminFinanceService adminFinance,
            String paymentId,
            RefundRequest request) throws InterruptedException {
        start.await();
        try {
            adminFinance.execute(
                    paymentId, request, "correlation-concurrent-admin");
            return new ConcurrentRefundOutcome(true, null);
        } catch (PaymentIntentException exception) {
            return new ConcurrentRefundOutcome(false, exception.code());
        }
    }

    private ConcurrentRefundOutcome returnRefundOutcomeAfter(
            CountDownLatch start,
            String paymentId,
            CreateReturnRefundRequest request) throws InterruptedException {
        start.await();
        try {
            returnRefundService.refund(
                    TOKEN, paymentId, "return-concurrent-refund-0160", request,
                    "correlation-concurrent-return");
            return new ConcurrentRefundOutcome(true, null);
        } catch (PaymentIntentException exception) {
            return new ConcurrentRefundOutcome(false, exception.code());
        }
    }

    private AdminFinanceService adminFinance(String paymentId, String orderId) {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        AdminFinanceAuthorizationClient authorization = mock(AdminFinanceAuthorizationClient.class);
        AdminFinanceOrderClient orders = mock(AdminFinanceOrderClient.class);
        AdminFinanceGovernanceClient governance = mock(AdminFinanceGovernanceClient.class);
        when(actors.currentActor()).thenReturn(new CurrentActor(
                "finance-subject", "finance-token", "finance@msb.local", "Finance Admin", true));
        when(authorization.requireAdmin("finance-token")).thenReturn(
                new AdminFinanceAuthorizationClient.Access(
                        id(180), List.of("PLATFORM_ADMIN"),
                        List.of(AdminFinanceService.FINANCE_READ, AdminFinanceService.REFUND_READ,
                                AdminFinanceService.REFUND_EXECUTE),
                        "ACTIVE"));
        when(orders.contexts(Set.of(paymentId))).thenReturn(Map.of(paymentId,
                new AdminFinanceOrderClient.Context(
                        paymentId, orderId, "MSB-CONCURRENCY", id(161),
                        new BigDecimal("50.0000"), "USD", Instant.now(),
                        List.of(), List.of(), List.of())));
        return new AdminFinanceService(
                actors, authorization, orders, governance, adminFinanceRepository,
                paymentProviders, paymentIds, objectMapper, transactionManager);
    }

    private record ConcurrentRefundOutcome(boolean succeeded, String code) { }

    private byte[] webhookBody(
            String eventId,
            String type,
            String providerReference,
            String failureCode) {
        String failureField = failureCode == null
                ? ""
                : ",\"failureCode\":\"" + failureCode + "\"";
        String json = """
                {
                  "eventId":"%s",
                  "type":"%s",
                  "occurredAt":"%s",
                  "data":{"providerReference":"%s"%s}
                }
                """.formatted(
                eventId,
                type,
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                providerReference,
                failureField);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String signature(byte[] body) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CreatePaymentIntentRequest request(
            String checkoutId,
            String buyerId,
            List<String> businessIds,
            String amount) {
        return new CreatePaymentIntentRequest(
                checkoutId,
                7,
                "a".repeat(64),
                buyerId,
                businessIds,
                new BigDecimal(amount),
                "USD",
                Instant.now().plusSeconds(900));
    }

    private String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }
}
