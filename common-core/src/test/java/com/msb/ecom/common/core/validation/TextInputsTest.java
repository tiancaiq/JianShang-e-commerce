package com.msb.ecom.common.core.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextInputsTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals("Seattle WA", TextInputs.collapseWhitespaceToEmpty("  Seattle\t\nWA  "));
        assertEquals("Seattle WA", TextInputs.requireCollapsed("Public city", "  Seattle   WA  ", 120));
    }

    @Test
    void convertsOptionalBlankTextToNull() {
        assertNull(TextInputs.collapseWhitespaceToNull(null));
        assertNull(TextInputs.collapseWhitespaceToNull("   "));
    }

    @Test
    void rejectsRequiredBlankOrTooLongText() {
        assertThrows(IllegalArgumentException.class,
                () -> TextInputs.requireCollapsed("Title", " ", 20));
        assertThrows(IllegalArgumentException.class,
                () -> TextInputs.requireCollapsed("Title", "this value is too long", 5));
    }
}
