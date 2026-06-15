package com.msb.ecom.common.web.correlation;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CorrelationId(String value) {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final int MAX_LENGTH = 128;

    private static final Pattern SAFE_VALUE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public CorrelationId {
        Objects.requireNonNull(value, "value");
        if (!SAFE_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid correlation ID");
        }
    }

    public static CorrelationId parse(String value) {
        return new CorrelationId(value);
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    public static CorrelationId acceptOrGenerate(String candidate) {
        if (candidate == null) {
            return generate();
        }
        try {
            return parse(candidate);
        } catch (IllegalArgumentException exception) {
            return generate();
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
