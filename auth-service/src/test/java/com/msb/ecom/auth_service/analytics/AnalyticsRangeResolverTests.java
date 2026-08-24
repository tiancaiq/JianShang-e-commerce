package com.msb.ecom.auth_service.analytics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsRangeResolverTests {
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private final AnalyticsRangeResolver resolver = new AnalyticsRangeResolver(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void resolvesHalfOpenCustomRangeAndEqualPreviousPeriod() {
        var range = resolver.resolve("CUSTOM", Instant.parse("2026-08-16T00:00:00Z"),
                Instant.parse("2026-08-23T00:00:00Z"), "UTC", true);

        assertThat(range.from()).isEqualTo(Instant.parse("2026-08-16T00:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));
        assertThat(range.comparisonFrom()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(range.comparisonTo()).isEqualTo(range.from());
    }

    @Test
    void infersCustomWhenExplicitBoundsAreSuppliedWithoutPreset() {
        var range = resolver.resolve(null, Instant.parse("2026-08-16T00:00:00Z"),
                Instant.parse("2026-08-23T00:00:00Z"), "UTC", false);

        assertThat(range.preset()).isEqualTo(AnalyticsContracts.RangePreset.CUSTOM);
        assertThat(range.from()).isEqualTo(Instant.parse("2026-08-16T00:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));
    }

    @Test
    void todayUsesUtcDayBoundary() {
        var range = resolver.resolve("TODAY", null, null, "UTC", false);

        assertThat(range.from()).isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.comparisonFrom()).isNull();
    }

    @Test
    void rejectsInvalidFutureAndUnboundedRanges() {
        assertThatThrownBy(() -> resolver.resolve("CUSTOM", NOW, NOW, "UTC", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("CUSTOM", NOW.minusSeconds(91L * 86400),
                NOW, "UTC", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("CUSTOM", NOW.minusSeconds(60),
                NOW.plusSeconds(1), "UTC", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("LAST_7_DAYS", null, null,
                "Asia/Shanghai", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesGranularityPointBounds() {
        var twoDays = resolver.resolve("CUSTOM", NOW.minusSeconds(48L * 3600), NOW,
                "UTC", false);
        resolver.validateGranularity(twoDays, Granularity.HOUR);

        var sevenDays = resolver.resolve("LAST_7_DAYS", null, null, "UTC", false);
        assertThatThrownBy(() -> resolver.validateGranularity(sevenDays, Granularity.HOUR))
                .isInstanceOf(IllegalArgumentException.class);
        resolver.validateGranularity(sevenDays, Granularity.DAY);
    }
}
