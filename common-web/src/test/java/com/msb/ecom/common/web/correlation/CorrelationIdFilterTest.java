package com.msb.ecom.common.web.correlation;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesMissingCorrelationId() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> downstreamHeader = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, (request, ignored) -> {
            downstreamHeader.set(((jakarta.servlet.http.HttpServletRequest) request)
                    .getHeader(CorrelationId.HEADER_NAME));
            assertEquals(downstreamHeader.get(), MDC.get(CorrelationIdFilter.MDC_KEY));
        });

        assertNotNull(downstreamHeader.get());
        assertEquals(downstreamHeader.get(), response.getHeader(CorrelationId.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void acceptsSafeClientCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER_NAME, "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrapped, ignored) ->
                assertEquals(
                        "client-request-123",
                        ((jakarta.servlet.http.HttpServletRequest) wrapped)
                                .getHeader(CorrelationId.HEADER_NAME)
                ));

        assertEquals("client-request-123", response.getHeader(CorrelationId.HEADER_NAME));
    }

    @Test
    void replacesInvalidAndOversizedCorrelationIds() throws ServletException, IOException {
        MockHttpServletRequest invalidRequest = new MockHttpServletRequest();
        invalidRequest.addHeader(CorrelationId.HEADER_NAME, "unsafe\r\nvalue");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalidRequest, invalidResponse, (request, response) -> {
        });

        MockHttpServletRequest oversizedRequest = new MockHttpServletRequest();
        oversizedRequest.addHeader(
                CorrelationId.HEADER_NAME,
                "a".repeat(CorrelationId.MAX_LENGTH + 1)
        );
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
        filter.doFilter(oversizedRequest, oversizedResponse, (request, response) -> {
        });

        assertNotEquals("unsafe\r\nvalue", invalidResponse.getHeader(CorrelationId.HEADER_NAME));
        assertNotEquals(
                "a".repeat(CorrelationId.MAX_LENGTH + 1),
                oversizedResponse.getHeader(CorrelationId.HEADER_NAME)
        );
        CorrelationId.parse(invalidResponse.getHeader(CorrelationId.HEADER_NAME));
        CorrelationId.parse(oversizedResponse.getHeader(CorrelationId.HEADER_NAME));
    }
}
