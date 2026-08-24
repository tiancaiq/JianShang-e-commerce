package com.msb.ecom.payment_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentAnalyticsServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-23T00:00:00Z");

    private final PaymentAnalyticsRepository repository = mock(PaymentAnalyticsRepository.class);
    private PaymentAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new PaymentAnalyticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.payments(any(), any(), anyBoolean())).thenReturn(
                new PaymentWindow(8, 2, new BigDecimal("80.00"), null));
        when(repository.refunds(any(), any(), anyBoolean())).thenReturn(
                new RefundWindow(3, 2, 1, 2, 1, 1, 0, 1, 0, 1, null));
        when(repository.refundBacklog(anyBoolean())).thenReturn(new PaymentAnalyticsRepository.BacklogRow(
                2, 1, Instant.parse("2026-08-22T12:00:00Z"), null));
    }

    @Test
    void summaryUsesHalfOpenCurrentAndEqualPreviousWindows() {
        Summary result = service.summary(FROM, TO, "UTC", true, false);

        assertThat(result.range().previousFrom()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(result.range().previousTo()).isEqualTo(FROM);
        assertThat(result.currentRefundBacklog().oldestActiveAgeSeconds()).isEqualTo(86_400);
        verify(repository).payments(FROM, TO, false);
        verify(repository).payments(Instant.parse("2026-08-09T00:00:00Z"), FROM, false);
        verify(repository).refundBacklog(false);
    }

    @Test
    void financialFlagReachesWindowAndCurrentBacklogQueries() {
        when(repository.payments(FROM, TO, true)).thenReturn(new PaymentWindow(
                1, 0, new BigDecimal("100.00"),
                List.of(new MoneyAmount("USD", new BigDecimal("42.0000")))));
        when(repository.refunds(FROM, TO, true)).thenReturn(new RefundWindow(
                1, 1, 0, 1, 0, 1, 0, 0, 0, 0,
                List.of(new RefundAmountMetrics("USD", new BigDecimal("10.0000"),
                        new BigDecimal("10.0000"), BigDecimal.ZERO))));
        when(repository.refundBacklog(true)).thenReturn(new PaymentAnalyticsRepository.BacklogRow(
                1, 0, FROM, List.of(new MoneyAmount("USD", new BigDecimal("5.0000")))));

        Summary result = service.summary(FROM, TO, "UTC", false, true);

        assertThat(result.current().payments().succeededPaymentAmounts()).hasSize(1);
        assertThat(result.current().refunds().amountMetrics()).hasSize(1);
        assertThat(result.currentRefundBacklog().activeAmounts()).hasSize(1);
        verify(repository).payments(FROM, TO, true);
        verify(repository).refunds(FROM, TO, true);
        verify(repository).refundBacklog(true);
    }

    @Test
    void invalidRangesAreRejectedBeforeRepositoriesRun() {
        assertThatThrownBy(() -> service.summary(FROM, FROM, "UTC", false, false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.summary(FROM, FROM.plusSeconds(90L * 86_400 + 1),
                "UTC", false, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.summary(FROM, TO, "Europe/Oslo", false, false))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void refundTrendZeroFillsUtcBuckets() {
        when(repository.trend(TrendMetric.REFUNDS_SUCCEEDED, Granularity.DAY, FROM, TO)).thenReturn(List.of(
                new TrendPoint(FROM, 2),
                new TrendPoint(FROM.plusSeconds(2 * 86_400), 1)));

        Trend trend = service.trend(TrendMetric.REFUNDS_SUCCEEDED, FROM, TO, "UTC", Granularity.DAY);

        assertThat(trend.points()).hasSize(7);
        assertThat(trend.points().get(0).value()).isEqualTo(2);
        assertThat(trend.points().get(1).value()).isZero();
        assertThat(trend.points().get(2).value()).isEqualTo(1);
    }

    @Test
    void hourlyTrendsAreBoundedToTwoDays() {
        assertThatThrownBy(() -> service.trend(TrendMetric.REFUNDS_SUCCEEDED, FROM, TO,
                "UTC", Granularity.HOUR)).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).trend(any(), any(), any(), any());
    }
}
