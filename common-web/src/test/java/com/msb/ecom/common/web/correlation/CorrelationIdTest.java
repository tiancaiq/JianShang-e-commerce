package com.msb.ecom.common.web.correlation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorrelationIdTest {

    @Test
    void acceptsSafeHeaderValue() {
        CorrelationId id = CorrelationId.parse("web:01JABCDEF-123");

        assertEquals("web:01JABCDEF-123", id.value());
        assertEquals(CorrelationId.HEADER_NAME, "X-Correlation-Id");
    }

    @Test
    void rejectsWhitespaceControlCharactersAndOversizedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> CorrelationId.parse("contains whitespace"));
        assertThrows(IllegalArgumentException.class,
                () -> CorrelationId.parse("line\r\nbreak"));
        assertThrows(IllegalArgumentException.class,
                () -> CorrelationId.parse("a".repeat(CorrelationId.MAX_LENGTH + 1)));
    }
}
