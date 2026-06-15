package com.msb.ecom.common.web.error;

import java.util.Objects;

public record ApiErrorEnvelope(ApiError error) {

    public ApiErrorEnvelope {
        Objects.requireNonNull(error, "error");
    }
}
