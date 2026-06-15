package com.msb.ecom.common.web.error;

import java.util.List;
import java.util.Objects;

public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String correlationId
) {

    public ApiError {
        code = requireText(code, "code");
        message = requireText(message, "message");
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        correlationId = requireText(correlationId, "correlationId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
