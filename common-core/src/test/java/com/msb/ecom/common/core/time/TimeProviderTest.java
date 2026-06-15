package com.msb.ecom.common.core.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeProviderTest {

    @Test
    void delegatesToInjectedClock() {
        Instant expected = Instant.parse("2026-06-15T12:00:00Z");
        TimeProvider provider = TimeProvider.from(Clock.fixed(expected, ZoneOffset.UTC));

        assertEquals(expected, provider.now());
    }
}
