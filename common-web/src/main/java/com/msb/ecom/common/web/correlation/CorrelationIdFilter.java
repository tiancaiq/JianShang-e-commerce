package com.msb.ecom.common.web.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE =
            CorrelationIdFilter.class.getName() + ".correlationId";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        CorrelationId correlationId =
                CorrelationId.acceptOrGenerate(request.getHeader(CorrelationId.HEADER_NAME));
        String value = correlationId.value();
        String previousMdcValue = MDC.get(MDC_KEY);

        HttpServletRequest wrappedRequest = new CorrelationRequestWrapper(request, value);
        wrappedRequest.setAttribute(REQUEST_ATTRIBUTE, value);
        response.setHeader(CorrelationId.HEADER_NAME, value);
        MDC.put(MDC_KEY, value);

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            if (previousMdcValue == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdcValue);
            }
        }
    }

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }
        return CorrelationId.generate().value();
    }

    private static final class CorrelationRequestWrapper extends HttpServletRequestWrapper {

        private final String correlationId;

        private CorrelationRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            if (CorrelationId.HEADER_NAME.equalsIgnoreCase(name)) {
                return correlationId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (CorrelationId.HEADER_NAME.equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singleton(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> existingNames = super.getHeaderNames();
            if (existingNames != null) {
                existingNames.asIterator().forEachRemaining(names::add);
            }
            names.add(CorrelationId.HEADER_NAME);
            return Collections.enumeration(names);
        }
    }
}
