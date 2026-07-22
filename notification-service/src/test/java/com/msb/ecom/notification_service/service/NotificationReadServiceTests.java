package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.config.NotificationReadProperties;
import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.msb.ecom.notification_service.service.NotificationCursorCodecTests.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationReadServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final String AUTHORIZATION = "Bearer opaque-token";

    private final NotificationActorIdentityClient identity = mock(NotificationActorIdentityClient.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationReadMetrics metrics =
            new NotificationReadMetrics(new SimpleMeterRegistry());
    private final NotificationPresentationMapper presentation =
            new NotificationPresentationMapper(new ObjectMapper());

    @Test
    void disabledGateRunsBeforeBearerQueryBodyIdentityOrRepository() {
        var service = service(false);

        assertCode(() -> service.list(null, "c", "bad+cursor", "999"),
                "NOTIFICATION_READ_API_NOT_AVAILABLE");
        assertCode(() -> service.markRead(null, "c", "bad", 12, "chunked"),
                "NOTIFICATION_READ_API_NOT_AVAILABLE");
        assertCode(() -> service.markAllRead(null, "c", 12, "chunked"),
                "NOTIFICATION_READ_API_NOT_AVAILABLE");

        verifyNoInteractions(identity, repository);
    }

    @Test
    void bearerShapePrecedesCursorAndIdentity() {
        assertCode(() -> service(true).list("Bearer bad token", "c", "bad+cursor", "999"),
                "NOTIFICATION_AUTHENTICATION_REQUIRED");
        verifyNoInteractions(identity, repository);
    }

    @Test
    void authFailuresMapWithoutRepositoryWork() {
        when(identity.requireActiveUserId(AUTHORIZATION, "c"))
                .thenThrow(new NotificationActorIdentityClient.DependencyUnavailableException());

        assertCode(() -> service(true).list(AUTHORIZATION, "c", null, null),
                "NOTIFICATION_IDENTITY_UNAVAILABLE");
        verifyNoInteractions(repository);
    }

    @Test
    void authenticationAndInactiveDenialsRemainDistinct() {
        when(identity.requireActiveUserId(AUTHORIZATION, "auth"))
                .thenThrow(new NotificationActorIdentityClient.AuthenticationRequiredException());
        when(identity.requireActiveUserId(AUTHORIZATION, "denied"))
                .thenThrow(new NotificationActorIdentityClient.AccessDeniedException());

        assertCode(() -> service(true).list(AUTHORIZATION, "auth", null, null),
                "NOTIFICATION_AUTHENTICATION_REQUIRED");
        assertCode(() -> service(true).list(AUTHORIZATION, "denied", null, null),
                "NOTIFICATION_ACCESS_DENIED");
        verifyNoInteractions(repository);
    }

    @Test
    void stablePageUsesResolvedRecipientAndReturnsBoundedProjection() {
        when(identity.requireActiveUserId(AUTHORIZATION, "c")).thenReturn(id(7));
        when(repository.findRecipientPage(eq(id(7)), eq(null), eq(null), eq(2)))
                .thenReturn(List.of(row(3, NOW), row(2, NOW.minusSeconds(1))));

        var result = service(true).list(AUTHORIZATION, "c", null, "1");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().presentationArgs())
                .isEqualTo(Map.of("orderId", id(1)));
        assertThat(result.page().hasMore()).isTrue();
        assertThat(result.page().nextCursor()).isNotBlank();
    }

    @Test
    void corruptProjectionFailsSafe() {
        when(identity.requireActiveUserId(AUTHORIZATION, "c")).thenReturn(id(7));
        when(repository.findRecipientPage(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new NotificationReadRow(
                        id(3), "ORDER_CONFIRMED", "ORDER_CONFIRMED_V1",
                        "{\"email\":\"hidden@example.invalid\"}", "/account", null, NOW)));

        assertCode(() -> service(true).list(AUTHORIZATION, "c", null, null),
                "NOTIFICATION_DATA_UNAVAILABLE");
    }

    @Test
    void markOneIsIdempotentAndCrossUserIsHidden() {
        when(identity.requireActiveUserId(AUTHORIZATION, "c")).thenReturn(id(7));
        when(repository.markOwnedRead(id(7), id(3), NOW)).thenReturn(true);
        when(repository.markOwnedRead(id(7), id(4), NOW)).thenReturn(false);

        service(true).markRead(AUTHORIZATION, "c", id(3), 0, null);
        service(true).markRead(AUTHORIZATION, "c", id(3), 0, null);
        assertCode(() -> service(true).markRead(AUTHORIZATION, "c", id(4), 0, null),
                "NOTIFICATION_NOT_FOUND");

        verify(repository, never()).markOwnedRead(eq(id(8)), any(), any());
    }

    @Test
    void commandValidationPrecedesIdentityAndReadAllLeaksNoCount() {
        assertCode(() -> service(true).markRead(AUTHORIZATION, "c", "bad", 0, null),
                "NOTIFICATION_ID_INVALID");
        assertCode(() -> service(true).markAllRead(AUTHORIZATION, "c", 2, null),
                "NOTIFICATION_BODY_NOT_ALLOWED");
        verifyNoInteractions(identity, repository);

        when(identity.requireActiveUserId(AUTHORIZATION, "c")).thenReturn(id(7));
        service(true).markAllRead(AUTHORIZATION, "c", 0, null);
        verify(repository).markAllOwnedRead(id(7), NOW);
    }

    @Test
    void logsCorrelationAndHashedActorWithoutBearerOrRawIds() {
        when(identity.requireActiveUserId(AUTHORIZATION, "safe-correlation")).thenReturn(id(7));
        when(repository.findRecipientPage(id(7), null, null, 21)).thenReturn(List.of());
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationReadService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(true).list(AUTHORIZATION, "safe-correlation", null, null);
        } finally {
            logger.detachAppender(appender);
        }

        String combined = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(combined)
                .contains("safe-correlation", "actorRef=")
                .doesNotContain(AUTHORIZATION, id(7));
    }

    private NotificationReadService service(boolean enabled) {
        return new NotificationReadService(
                new NotificationReadProperties(
                        enabled,
                        "http://auth.test",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3)),
                identity,
                repository,
                presentation,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NotificationReadRow row(int suffix, Instant createdAt) {
        return new NotificationReadRow(
                id(suffix),
                "ORDER_CONFIRMED",
                "ORDER_CONFIRMED_V1",
                "{\"orderId\":\"" + id(1) + "\"}",
                "/account",
                null,
                createdAt);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(NotificationReadException.class)
                .extracting(exception -> ((NotificationReadException) exception).code())
                .isEqualTo(code);
    }
}
