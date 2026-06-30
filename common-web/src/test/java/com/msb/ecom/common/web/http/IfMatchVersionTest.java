package com.msb.ecom.common.web.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IfMatchVersionTest {

    private static final String ERROR_MESSAGE = "If-Match must contain the current resource version";

    @Test
    void parsesUnquotedVersion() {
        assertEquals(7L, IfMatchVersion.parseRequired("7", ERROR_MESSAGE));
    }

    @Test
    void parsesQuotedVersion() {
        assertEquals(7L, IfMatchVersion.parseRequired("\"7\"", ERROR_MESSAGE));
    }

    @Test
    void rejectsMissingVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> IfMatchVersion.parseRequired(null, ERROR_MESSAGE));

        assertEquals(ERROR_MESSAGE, exception.getMessage());
    }

    @Test
    void rejectsBlankVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> IfMatchVersion.parseRequired("   ", ERROR_MESSAGE));

        assertEquals(ERROR_MESSAGE, exception.getMessage());
    }

    @Test
    void rejectsInvalidVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> IfMatchVersion.parseRequired("abc", ERROR_MESSAGE));

        assertEquals(ERROR_MESSAGE, exception.getMessage());
    }

    @Test
    void rejectsEmptyQuotedVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> IfMatchVersion.parseRequired("\"\"", ERROR_MESSAGE));

        assertEquals(ERROR_MESSAGE, exception.getMessage());
    }
}
