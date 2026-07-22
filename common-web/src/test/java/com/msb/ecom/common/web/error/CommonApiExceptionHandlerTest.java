package com.msb.ecom.common.web.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonApiExceptionHandlerTest {

    private final CommonApiExceptionHandler handler = new CommonApiExceptionHandler();

    @Test
    void unexpectedErrorContainsSafeEnvelopeWithoutStackTraceOrSecret() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "request-123");

        ResponseEntity<ApiErrorEnvelope> response =
                handler.handleUnexpected(
                        new RuntimeException("password=top-secret\nat internal.Stack.trace"),
                        request
                );

        ApiError error = response.getBody().error();
        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", error.code());
        assertEquals("An unexpected error occurred.", error.message());
        assertEquals("request-123", error.correlationId());
        assertFalse(error.message().contains("top-secret"));
        assertFalse(error.message().contains("Stack"));
        assertNotNull(response.getBody());
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    @ResourceLock(Resources.SYSTEM_ERR)
    void unexpectedErrorLogExcludesMessagesCausesAndSuppressedExceptions() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "correlation-safe-456");

        RuntimeException nested = new RuntimeException(
                "password=SecretPassword! token=secret-token email=person@example.com",
                new IllegalStateException("phone=+1-949-555-0100 address=123 Private Street")
        );
        nested.addSuppressed(new IllegalArgumentException("suppressed-api-key=secret-key"));

        Logger logger = (Logger) LoggerFactory.getLogger(CommonApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

            handler.handleUnexpected(nested, request);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        String captured = event.getFormattedMessage()
                + capturedOut.toString(StandardCharsets.UTF_8)
                + capturedErr.toString(StandardCharsets.UTF_8);

        assertTrue(event.getFormattedMessage().contains("event=COMMON_WEB_UNEXPECTED_EXCEPTION"));
        assertTrue(event.getFormattedMessage().contains("correlationId=correlation-safe-456"));
        assertTrue(event.getFormattedMessage().contains("exceptionCategory=RUNTIME_EXCEPTION"));
        assertNull(event.getThrowableProxy());
        assertFalse(captured.contains("SecretPassword"));
        assertFalse(captured.contains("secret-token"));
        assertFalse(captured.contains("person@example.com"));
        assertFalse(captured.contains("+1-949-555-0100"));
        assertFalse(captured.contains("123 Private Street"));
        assertFalse(captured.contains("secret-key"));
        assertFalse(captured.contains("suppressed-api-key"));
    }
}
