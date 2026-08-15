package com.msb.ecom.notification_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRequestBodyGuardTests {
    @Test
    void acceptsChunkFramingWhenTheGatewayForwardedNoBodyBytes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");

        assertThat(NotificationRequestBodyGuard.actualContentLength(request)).isZero();
    }

    @Test
    void rejectsChunkedCommandsThatContainAnyBodyBytes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        request.setContent("{}".getBytes());

        assertThat(NotificationRequestBodyGuard.actualContentLength(request)).isPositive();
    }
}
