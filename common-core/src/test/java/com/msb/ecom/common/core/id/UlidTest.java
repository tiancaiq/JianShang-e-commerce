package com.msb.ecom.common.core.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UlidTest {

    @Test
    void acceptsCanonicalUppercaseValue() {
        Ulid ulid = Ulid.parse("01JABCDEFGHJKMNPQRSTVWXYZ0");

        assertEquals("01JABCDEFGHJKMNPQRSTVWXYZ0", ulid.value());
        assertEquals(ulid.value(), ulid.toString());
    }

    @Test
    void rejectsLowercaseAndAmbiguousCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> Ulid.parse("01jabcdefghjkmnpqrstvwxyz0"));
        assertThrows(IllegalArgumentException.class,
                () -> Ulid.parse("01JABCDEFGHIKMNPQRSTVWXYZ0"));
    }
}
