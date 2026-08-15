package com.msb.ecom.product_service.search;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BodylessAdminCommandValidatorTests {
    @Test
    void ioFailureMapsToCallerBoundedUnavailableException() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        IOException ioFailure = new IOException("transport closed");
        RuntimeException unavailable = new RuntimeException("bounded unavailable");
        when(request.getInputStream()).thenThrow(ioFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                BodylessAdminCommandValidator.rejectDecodedBodyBytes(
                        request,
                        exception -> unavailable));

        assertSame(unavailable, thrown);
    }

    @Test
    void decodedByteRejectsWithoutReadingFurther() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream inputStream = mock(ServletInputStream.class);
        when(inputStream.read()).thenReturn((int) '{');
        when(request.getInputStream()).thenReturn(inputStream);

        assertThrows(IllegalArgumentException.class, () ->
                BodylessAdminCommandValidator.rejectDecodedBodyBytes(
                        request,
                        RuntimeException::new));
    }
}
