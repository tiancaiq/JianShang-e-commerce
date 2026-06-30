package com.msb.ecom.common.core.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedLengthIdsTest {

    @Test
    void trimsAndAcceptsExpectedLengthId() {
        assertEquals("01JABCDEFGHJKMNPQRSTVWXYZ0",
                FixedLengthIds.requireTrimmed("Listing ID", " 01JABCDEFGHJKMNPQRSTVWXYZ0 ", 26));
    }

    @Test
    void rejectsBlankOrWrongLengthId() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedLengthIds.requireTrimmed("Listing ID", " ", 26));
        assertThrows(IllegalArgumentException.class,
                () -> FixedLengthIds.requireTrimmed("Listing ID", "short", 26));
    }
}
