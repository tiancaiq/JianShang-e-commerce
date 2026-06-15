package com.msb.ecom.common.core.id;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical, uppercase ULID value used by new public application resources.
 */
public record Ulid(String value) {

    private static final Pattern CANONICAL_ULID =
            Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    public Ulid {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL_ULID.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a canonical uppercase ULID");
        }
    }

    public static Ulid parse(String value) {
        return new Ulid(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
