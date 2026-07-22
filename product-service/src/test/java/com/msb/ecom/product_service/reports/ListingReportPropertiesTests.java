package com.msb.ecom.product_service.reports;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingReportPropertiesTests {

    @Test
    void disabledDefaultsRemainStartupSafe() {
        assertThatCode(() -> properties(false, false, "disabled-only-listing-report-hmac-key").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void enabledIntakeRequiresRetentionApprovalAndNonDefaultHmacSecret() {
        assertThatThrownBy(() -> properties(true, false, "x".repeat(40)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention-policy approval");
        assertThatThrownBy(() -> properties(
                true,
                true,
                "disabled-only-listing-report-hmac-key").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-default abuse HMAC secret");
        assertThatCode(() -> properties(true, true, "x".repeat(40)).validate())
                .doesNotThrowAnyException();
    }

    private ListingReportProperties properties(
            boolean enabled,
            boolean retentionApproved,
            String hmacSecret) {
        return new ListingReportProperties(
                enabled,
                retentionApproved,
                false,
                hmacSecret,
                "REP-00-V1",
                "listing-report-v1",
                Duration.ofDays(90),
                Duration.ofHours(24),
                Duration.ofDays(180),
                Duration.ofDays(730),
                5,
                20);
    }
}
