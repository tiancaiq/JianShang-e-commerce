package com.msb.ecom.notification_service.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.io.IOException;

final class NotificationRequestBodyGuard {
    private NotificationRequestBodyGuard() {}

    // Distinguishes gateway chunk framing from an actual command body.
    static long actualContentLength(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > 0) return declared;
        String transferEncoding = request.getHeader(HttpHeaders.TRANSFER_ENCODING);
        if (transferEncoding == null || transferEncoding.isBlank()) return 0;
        try {
            return request.getInputStream().read() == -1 ? 0 : 1;
        } catch (IOException exception) {
            return 1;
        }
    }
}
