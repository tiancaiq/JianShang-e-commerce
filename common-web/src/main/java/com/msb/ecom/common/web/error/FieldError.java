package com.msb.ecom.common.web.error;

import java.util.Objects;

public record FieldError(String field, String code) {

    public FieldError {
        field = requireText(field, "field");
        code = requireText(code, "code");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
