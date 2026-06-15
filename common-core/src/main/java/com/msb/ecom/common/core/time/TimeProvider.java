package com.msb.ecom.common.core.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@FunctionalInterface
public interface TimeProvider {

    Instant now();

    static TimeProvider systemUtc() {
        return from(Clock.systemUTC());
    }

    static TimeProvider from(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return clock::instant;
    }
}
