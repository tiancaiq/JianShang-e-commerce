package com.msb.ecom.notification_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.notification_service.config.NotificationReadProperties;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import com.msb.ecom.notification_service.service.NotificationActorIdentityClient;
import com.msb.ecom.notification_service.service.NotificationPresentationMapper;
import com.msb.ecom.notification_service.service.NotificationReadMetrics;
import com.msb.ecom.notification_service.service.NotificationReadService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTests {

    private final NotificationActorIdentityClient identity = mock(NotificationActorIdentityClient.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvc(true);
    }

    @Test
    void listsThroughResolvedActorAndIgnoresSpoofedIdentityHeaders() throws Exception {
        when(identity.requireActiveUserId("Bearer actor-token", "notification-api-1"))
                .thenReturn(id(7));
        when(repository.findRecipientPage(id(7), null, null, 21)).thenReturn(List.of(
                new com.msb.ecom.notification_service.model.NotificationReadRow(
                        id(3),
                        "ORDER_CONFIRMED",
                        "ORDER_CONFIRMED_V1",
                        "{\"orderId\":\"" + id(1) + "\"}",
                        "/account",
                        null,
                        Instant.parse("2026-07-20T12:00:00Z"))));

        mvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer actor-token")
                        .header("X-Correlation-Id", "notification-api-1")
                        .header("X-User-Id", id(9))
                        .header("X-Actor-Id", id(9)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "notification-api-1"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].presentationArgs.orderId").value(id(1)))
                .andExpect(jsonPath("$.data.items[0].recipientUserId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].sourceEventId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].rawJson").doesNotExist())
                .andExpect(jsonPath("$.data.page.hasMore").value(false));

        verify(identity).requireActiveUserId("Bearer actor-token", "notification-api-1");
        verify(repository).findRecipientPage(id(7), null, null, 21);
    }

    @Test
    void noBodyCommandsReturn204AndBodiesAreRejectedBeforeAuth() throws Exception {
        when(identity.requireActiveUserId("Bearer actor-token", "notification-api-2"))
                .thenReturn(id(7));
        when(repository.markOwnedRead(any(), any(), any())).thenReturn(true);

        mvc.perform(post("/api/v1/notifications/{id}/read", id(3))
                        .header("Authorization", "Bearer actor-token")
                        .header("X-Correlation-Id", "notification-api-2"))
                .andExpect(status().isNoContent());

        clearInvocations(identity, repository);

        mvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer actor-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_BODY_NOT_ALLOWED"));
        verifyNoInteractions(identity, repository);
    }

    @Test
    void disabledApiReturnsStable404BeforeAnyDownstreamWork() throws Exception {
        mvc = mvc(false);

        mvc.perform(get("/api/v1/notifications?cursor=bad+cursor&limit=999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("NOTIFICATION_READ_API_NOT_AVAILABLE"));

        verifyNoInteractions(identity, repository);
    }

    @Test
    void bearerShapePrecedesQueryValidation() throws Exception {
        mvc.perform(get("/api/v1/notifications?cursor=bad+cursor&limit=999")
                        .header("Authorization", "Basic not-bearer"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("NOTIFICATION_AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(identity, repository);
    }

    private MockMvc mvc(boolean enabled) {
        NotificationReadService service = new NotificationReadService(
                new NotificationReadProperties(
                        enabled,
                        "http://auth.test",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3)),
                identity,
                repository,
                new NotificationPresentationMapper(new ObjectMapper()),
                new NotificationReadMetrics(new SimpleMeterRegistry()));
        return MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new NotificationReadExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }
}
