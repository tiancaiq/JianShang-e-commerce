package com.msb.ecom.common.web.error;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiErrorTest {

    @Test
    void defensivelyCopiesFieldErrors() {
        List<FieldError> fields = new ArrayList<>();
        fields.add(new FieldError("quantity", "OUT_OF_RANGE"));

        ApiError error = new ApiError(
                "INVALID_REQUEST",
                "The request is invalid.",
                fields,
                "request-123"
        );
        fields.clear();

        assertEquals(1, error.fieldErrors().size());
        assertThrows(UnsupportedOperationException.class,
                () -> error.fieldErrors().add(new FieldError("title", "REQUIRED")));
    }

    @Test
    void usesEmptyFieldErrorsWhenNotProvided() {
        ApiError error = new ApiError("NOT_FOUND", "Not found.", null, "request-123");

        assertEquals(List.of(), error.fieldErrors());
    }
}
