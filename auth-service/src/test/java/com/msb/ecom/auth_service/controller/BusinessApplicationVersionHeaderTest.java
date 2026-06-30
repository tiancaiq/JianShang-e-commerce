package com.msb.ecom.auth_service.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessApplicationVersionHeaderTest {

    @Test
    void parsesPlainAndQuotedVersionHeaders() {
        assertThat(BusinessApplicationVersionHeader.parse("7")).isEqualTo(7L);
        assertThat(BusinessApplicationVersionHeader.parse("\"8\"")).isEqualTo(8L);
    }

    @Test
    void rejectsMissingOrInvalidVersionHeaders() {
        assertThatThrownBy(() -> BusinessApplicationVersionHeader.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("If-Match must contain the current business application version");
        assertThatThrownBy(() -> BusinessApplicationVersionHeader.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("If-Match must contain the current business application version");
        assertThatThrownBy(() -> BusinessApplicationVersionHeader.parse("current"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("If-Match must contain the current business application version");
    }
}
