package com.msb.ecom.common.web.error;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
