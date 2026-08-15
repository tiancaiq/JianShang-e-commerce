package com.msb.ecom.product_service.search;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.function.Function;

public final class BodylessAdminCommandValidator {
    private BodylessAdminCommandValidator() {
    }

    // Admin command POSTs accept legal empty transport framing, but no decoded payload bytes.
    public static void rejectDecodedBodyBytes(
            HttpServletRequest request,
            Function<IOException, RuntimeException> unavailable) {
        try {
            if (request.getInputStream().read() != -1) {
                throw new IllegalArgumentException("Request body is not allowed.");
            }
        } catch (IOException exception) {
            throw unavailable.apply(exception);
        }
    }
}
