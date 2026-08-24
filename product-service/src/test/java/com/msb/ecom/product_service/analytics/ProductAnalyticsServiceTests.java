package com.msb.ecom.product_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.*;
import static com.msb.ecom.product_service.analytics.ProductAnalyticsRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAnalyticsServiceTests {
    private static final Instant GENERATED_AT = Instant.parse("2026-08-10T00:00:00Z");

    @Mock
    private ProductAnalyticsRepository repository;

    private ProductAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new ProductAnalyticsService(repository,
                Clock.fixed(GENERATED_AT, ZoneOffset.UTC));
    }

    @Test
    void composesCurrentSnapshotsAndSelectedRangeFlowsWithoutMixingThem() {
        Instant from = Instant.parse("2026-05-12T00:00:00Z");
        Instant to = Instant.parse("2026-08-10T00:00:00Z");
        CategoryCount currentCategory = new CategoryCount("category-a", "Category A", 5);
        CategoryCount newCategory = new CategoryCount("category-b", "Category B", 2);
        when(repository.listingStates()).thenReturn(Map.of("DRAFT", 2L, "ACTIVE", 3L));
        when(repository.newListings(from, to)).thenReturn(2L);
        when(repository.moderationQueue()).thenReturn(new ModerationQueueRow(
                4, 3, 1, Instant.parse("2026-08-09T00:00:00Z")));
        when(repository.moderationResolutions(from, to))
                .thenReturn(new ModerationResolutionRow(2, 60L));
        when(repository.moderationDecisions(from, to))
                .thenReturn(new ModerationDecisionRow(4, 2, 1, 1));
        when(repository.topCategoriesByCurrentListings()).thenReturn(List.of(currentCategory));
        when(repository.topCategoriesByNewListings(from, to)).thenReturn(List.of(newCategory));
        when(repository.enforcementCreatedByAction(from, to)).thenReturn(List.of(
                new BreakdownRow("RESTRICT", 1), new BreakdownRow("SUSPEND", 2)));
        when(repository.enforcementActiveByAction(GENERATED_AT)).thenReturn(List.of(
                new BreakdownRow("RESTRICT", 2), new BreakdownRow("SUSPEND", 1)));
        when(repository.enforcementCreatedByScope(from, to)).thenReturn(List.of(
                new BreakdownRow("LISTING_PUBLIC_VISIBILITY", 2)));
        when(repository.enforcementActiveByScope(GENERATED_AT)).thenReturn(List.of(
                new BreakdownRow("LISTING_PUBLIC_VISIBILITY", 1),
                new BreakdownRow("LISTING_PURCHASABILITY", 1)));

        Summary result = service.summary(from, to);

        assertThat(result.range()).isEqualTo(new AnalyticsRange(from, to, "UTC"));
        assertThat(result.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(result.listings().totalListings()).isEqualTo(5);
        assertThat(result.listings().draftListings()).isEqualTo(2);
        assertThat(result.listings().activeListings()).isEqualTo(3);
        assertThat(result.listings().newListings()).isEqualTo(2);
        assertThat(result.moderation().openCount()).isEqualTo(4);
        assertThat(result.moderation().oldestOpenAgeSeconds()).isEqualTo(86_400);
        assertThat(result.moderation().resolvedInRange()).isEqualTo(2);
        assertThat(result.moderation().rejectionRate()).isEqualByComparingTo("0.2500");
        assertThat(result.categories().topByCurrentListings()).containsExactly(currentCategory);
        assertThat(result.categories().topByNewListings()).containsExactly(newCategory);
        assertThat(result.listingEnforcement().createdInRange()).isEqualTo(3);
        assertThat(result.listingEnforcement().currentActive()).isEqualTo(3);
        assertThat(result.listingEnforcement().byActionType())
                .containsExactly(new EnforcementActionBreakdown("RESTRICT", 1, 2),
                        new EnforcementActionBreakdown("SUSPEND", 2, 1));
        assertThat(result.listingEnforcement().byScope())
                .containsExactly(new EnforcementScopeBreakdown("LISTING_PUBLIC_VISIBILITY", 2, 1),
                        new EnforcementScopeBreakdown("LISTING_PURCHASABILITY", 0, 1));
    }

    @Test
    void rejectsEmptyReversedAndOverNinetyDayRangesBeforeReadingTheDatabase() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");

        assertBadRequest(() -> service.summary(from, from));
        assertBadRequest(() -> service.summary(from.plusSeconds(1), from));
        assertBadRequest(() -> service.summary(from, from.plusSeconds(90L * 86_400 + 1)));

        verifyNoInteractions(repository);
    }

    @Test
    void buildsUtcHalfOpenTrendWithClippedEdgesAndZeroFilledBuckets() {
        Instant from = Instant.parse("2026-08-01T00:30:00Z");
        Instant to = Instant.parse("2026-08-01T03:00:00Z");
        when(repository.listingCreatedBuckets(from, to, Granularity.HOUR)).thenReturn(Map.of(
                Instant.parse("2026-08-01T00:00:00Z"), 3L,
                Instant.parse("2026-08-01T02:00:00Z"), 2L));

        ListingCreatedTrend result = service.listingCreatedTrend(from, to, Granularity.HOUR);

        assertThat(result.metric()).isEqualTo("LISTINGS_CREATED");
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.points()).containsExactly(
                new TrendPoint(from, Instant.parse("2026-08-01T01:00:00Z"), 3),
                new TrendPoint(Instant.parse("2026-08-01T01:00:00Z"),
                        Instant.parse("2026-08-01T02:00:00Z"), 0),
                new TrendPoint(Instant.parse("2026-08-01T02:00:00Z"), to, 2));
    }

    @Test
    void boundsHourlyTrendToFortyEightHours() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");

        assertBadRequest(() -> service.listingCreatedTrend(
                from, from.plusSeconds(48L * 3600 + 1), Granularity.HOUR));

        verifyNoInteractions(repository);
    }

    private void assertBadRequest(Runnable invocation) {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(invocation::run)
                .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
