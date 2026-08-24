package com.msb.ecom.order_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderAnalyticsServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-23T00:00:00Z");

    private final OrderAnalyticsRepository repository = mock(OrderAnalyticsRepository.class);
    private OrderAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new OrderAnalyticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.orders(any(), any(), anyBoolean())).thenReturn(
                new OrderWindow(4, 4, 1, 2, null));
        when(repository.disputes(any(), any())).thenReturn(new DisputeWindow(3, 2, 1, 0, 1, 0));
        when(repository.disputeBacklog()).thenReturn(new OrderAnalyticsRepository.BacklogRow(
                5, 3, Instant.parse("2026-08-22T12:00:00Z")));
    }

    @Test
    void summaryUsesHalfOpenCurrentAndEqualPreviousWindows() {
        Summary result = service.summary(FROM, TO, "UTC", true, false);

        assertThat(result.range().previousFrom()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(result.range().previousTo()).isEqualTo(FROM);
        assertThat(result.currentDisputeBacklog().oldestOpenAgeSeconds()).isEqualTo(86_400);
        verify(repository).orders(FROM, TO, false);
        verify(repository).orders(Instant.parse("2026-08-09T00:00:00Z"), FROM, false);
    }

    @Test
    void financialQueryFlagIsPassedOnlyWhenExplicitlyRequested() {
        when(repository.orders(FROM, TO, true)).thenReturn(new OrderWindow(
                1, 1, 0, 0, List.of(new MoneyAmount("USD", new BigDecimal("42.0000")))));

        Summary result = service.summary(FROM, TO, "UTC", false, true);

        assertThat(result.current().orders().grossCreatedAmounts()).singleElement()
                .satisfies(amount -> assertThat(amount.amount()).isEqualByComparingTo("42.0000"));
        verify(repository).orders(FROM, TO, true);
        verify(repository, never()).orders(any(), eq(FROM), eq(true));
    }

    @Test
    void rangeValidationRejectsEmptyOversizedAndNonUtcRangesBeforeQuerying() {
        assertThatThrownBy(() -> service.summary(FROM, FROM, "UTC", false, false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.summary(FROM, FROM.plusSeconds(90L * 86_400 + 1),
                "UTC", false, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.summary(FROM, TO, "Asia/Shanghai", false, false))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void trendsAreAllowlistedAndZeroFillUtcBuckets() {
        when(repository.trend(TrendMetric.ORDERS_CREATED, Granularity.DAY, FROM, TO)).thenReturn(List.of(
                new TrendPoint(Instant.parse("2026-08-16T00:00:00Z"), 2),
                new TrendPoint(Instant.parse("2026-08-18T00:00:00Z"), 1)));

        Trend trend = service.trend(TrendMetric.ORDERS_CREATED, FROM, TO, "UTC", Granularity.DAY);

        assertThat(trend.points()).hasSize(7);
        assertThat(trend.points().get(0).value()).isEqualTo(2);
        assertThat(trend.points().get(1).value()).isZero();
        assertThat(trend.points().get(2).value()).isEqualTo(1);
    }

    @Test
    void hourlyTrendsAreBoundedToTwoDays() {
        assertThatThrownBy(() -> service.trend(TrendMetric.DISPUTES_OPENED, FROM, TO,
                "UTC", Granularity.HOUR)).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).trend(any(), any(), any(), any());
    }
}
