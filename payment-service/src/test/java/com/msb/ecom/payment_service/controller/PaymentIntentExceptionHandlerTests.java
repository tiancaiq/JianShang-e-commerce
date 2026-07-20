package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIntentExceptionHandlerTests {

    @Test
    void unexpectedStateUsesPaymentSafeInternalErrorWithoutLeakingMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "correlation-handler-test");
        PaymentIntentExceptionHandler handler = new PaymentIntentExceptionHandler();

        var response = handler.handleUnexpectedState(
                new IllegalStateException("provider-secret-and-raw-payload"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("PAYMENT_INTERNAL_ERROR");
        assertThat(response.getBody().error().message())
                .isEqualTo("An unexpected payment error occurred.")
                .doesNotContain("provider-secret", "raw-payload");
        assertThat(response.getBody().error().correlationId())
                .isEqualTo("correlation-handler-test");
    }
}
