package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.system.SystemOperationsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.*;
import static com.msb.ecom.auth_service.analytics.AuthAnalyticsRepository.*;
import static com.msb.ecom.auth_service.analytics.OwnerAnalyticsContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    @Mock AuthService auth;
    @Mock AdminAuthorizationService authorization;
    @Mock AuthAnalyticsRepository repository;
    @Mock AnalyticsOwnerClient owners;
    @Mock SystemOperationsClient systems;
    private User user;
    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        user = User.create("01000000000000000000000001", "analytics-user",
                "analytics@example.test", true, "Analytics", "analytics-reader");
        when(auth.ensureUserEntity()).thenReturn(user);
        when(authorization.requirePermission(user, AdminPermission.ANALYTICS_READ))
                .thenReturn(user.getId());
        service = new AdminAnalyticsService(auth, authorization, repository, owners, systems,
                new AnalyticsComparisonCalculator(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    @Test
    void partialOwnerFailureKeepsLocalSectionsAvailableAndDoesNotRequestMoney() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Order unavailable."));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Payment unavailable."));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.unavailable("Catalog unavailable."));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.capabilities().financialAmounts()).isFalse();
        assertThat(result.marketplace().status()).isEqualTo(Availability.DEGRADED);
        assertThat(result.commerce().status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.support().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.catalog().status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.operations().status()).isEqualTo(Availability.RESTRICTED);
        assertThat(result.governance().status()).isEqualTo(Availability.RESTRICTED);
        assertThat(result.trustAndSafety().status()).isEqualTo(Availability.DEGRADED);
        assertThat(result.trustAndSafety().safeMessage()).contains("Catalog unavailable.");
        assertThat(result.trustAndSafety().metrics()).extracting(AnalyticsMetric::key)
                .contains("openInvestigationCases", "oldestOpenCaseAge",
                        "pendingAppeals", "unassignedAppeals",
                        "oldestPendingAppealAge", "userAndBusinessEnforcementActionsCreated",
                        "appealsFinalized", "appealsUpheld", "appealsModified",
                        "appealsRevoked", "appealAdjustmentRate")
                .doesNotContain("listingEnforcementActionsCreated",
                        "activeListingEnforcementActions");
        verify(owners).paymentSummary(any(), any(), eq(false), eq(false), anyString());
    }

    @Test
    void finalizedAppealMetricsUseOnlyAuthoritativeFinalStatesAndProduceExactAdjustmentRate() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(orderSummary()));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(paymentSummary()));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.trustAndSafety().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.trustAndSafety().safeMessage()).isNull();
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealsFinalized"))
                .singleElement().extracting(AnalyticsMetric::currentValue)
                .isEqualTo(BigDecimal.valueOf(4));
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealsUpheld"))
                .singleElement().extracting(AnalyticsMetric::currentValue)
                .isEqualTo(BigDecimal.valueOf(2));
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealsModified"))
                .singleElement().extracting(AnalyticsMetric::currentValue)
                .isEqualTo(BigDecimal.ONE);
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealsRevoked"))
                .singleElement().extracting(AnalyticsMetric::currentValue)
                .isEqualTo(BigDecimal.ONE);
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealAdjustmentRate"))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.currentValue()).isEqualByComparingTo("50.00");
                    assertThat(metric.unit()).isEqualTo(MetricUnit.PERCENT);
                });
    }

    @Test
    void appealAdjustmentRateIsUndefinedWhenNoAppealsWereFinalized() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(repository.trustWindow(any(), any()))
                .thenReturn(new TrustWindow(3, 1, 1, 1, 0, 1, 1, 0, 0, 0, 0));
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(orderSummary()));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(paymentSummary()));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("appealAdjustmentRate"))
                .singleElement().extracting(AnalyticsMetric::currentValue).isNull();
    }

    @Test
    void analyticsPermissionIsAuthoritativeBeforeAnyAggregateRead() {
        reset(authorization);
        when(authorization.requirePermission(user, AdminPermission.ANALYTICS_READ))
                .thenThrow(new com.msb.ecom.auth_service.service.BusinessApplicationForbiddenException());

        assertThatThrownBy(() -> service.overview(range(false), "correlation"))
                .isInstanceOf(com.msb.ecom.auth_service.service.BusinessApplicationForbiddenException.class);
        verifyNoInteractions(repository, owners, systems);
    }

    @Test
    void backendOmitsOwnerMoneyBreakdownsWithoutFinancePermission() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(orderSummary()));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(paymentSummary()));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.commerce().breakdowns())
                .extracting(AnalyticsBreakdown::key)
                .doesNotContain("grossOrderAmountsCreated", "succeededPaymentAmounts",
                        "refundAmounts", "activeRefundAmounts");
        assertThat(result.moderation().metrics())
                .filteredOn(metric -> metric.key().equals("listingModerationRejectionRate"))
                .singleElement().extracting(AnalyticsMetric::currentValue)
                .isEqualTo(BigDecimal.valueOf(100));
        assertThat(result.catalog().metrics()).extracting(AnalyticsMetric::key)
                .contains("activeListingEnforcementActions")
                .doesNotContain("listingsWithActiveSuspensionEnforcement");
        assertThat(result.trustAndSafety().breakdowns()).extracting(AnalyticsBreakdown::key)
                .contains("enforcementByTargetAndAction", "enforcementByTargetAndScope");
        assertThat(result.commerce().metrics()).extracting(AnalyticsMetric::key)
                .contains("oldestOpenDisputeAge", "fullRefundsSucceeded",
                        "partialRefundsSucceeded", "fullRefundsFailed",
                        "partialRefundsFailed", "returnRefundsSucceeded",
                        "oldestActiveRefundAge");
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("unassignedReports"))
                .singleElement().extracting(AnalyticsMetric::drillDownKey)
                .isEqualTo(DrillDownKey.UNASSIGNED_REPORTS);
    }

    @Test
    void supportQueryFailureDegradesOnlyTheSupportSection() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(repository.supportWindow(any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(orderSummary()));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.available(paymentSummary()));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.support().status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.support().safeMessage()).contains("Support analytics");
        assertThat(result.marketplace().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.moderation().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.commerce().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.catalog().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.trustAndSafety().metrics()).isNotEmpty();
    }

    @Test
    void financeAuthorizedReaderGetsAmountBreakdownsAndSystemGovernanceRemainSeparate() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("SUPER_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id(),
                AdminPermission.FINANCE_READ.id(), AdminPermission.GOVERNANCE_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(true), anyString()))
                .thenReturn(OwnerResult.available(orderSummary()));
        when(owners.paymentSummary(any(), any(), eq(false), eq(true), anyString()))
                .thenReturn(OwnerResult.available(paymentSummary()));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()));

        AdminAnalyticsOverview result = service.overview(range(false), "correlation");

        assertThat(result.capabilities().financialAmounts()).isTrue();
        assertThat(result.commerce().breakdowns()).extracting(AnalyticsBreakdown::key)
                .contains("grossOrderAmountsCreated", "succeededPaymentAmounts",
                        "refundAmounts", "activeRefundAmounts");
        assertThat(result.governance().status()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.operations().status()).isEqualTo(Availability.RESTRICTED);
    }

    @Test
    void missingPreviousProductWindowDegradesDependentSectionsWithoutInventingZero() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(true), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Order unavailable."));
        when(owners.paymentSummary(any(), any(), eq(true), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Payment unavailable."));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.available(productSummary()),
                        OwnerResult.unavailable("Previous catalog analytics unavailable."));

        AdminAnalyticsOverview result = service.overview(range(true), "correlation");

        assertThat(result.marketplace().status()).isEqualTo(Availability.DEGRADED);
        assertThat(result.moderation().status()).isEqualTo(Availability.DEGRADED);
        assertThat(result.catalog().status()).isEqualTo(Availability.DEGRADED);
        assertThat(result.catalog().safeMessage()).contains("Previous catalog analytics unavailable");
        assertThat(result.trustAndSafety().metrics())
                .filteredOn(metric -> metric.key().equals("listingEnforcementActionsCreated"))
                .singleElement().extracting(AnalyticsMetric::previousValue).isNull();
    }

    @Test
    void localTrendIsZeroFilledAndClippedToTheRequestedRange() {
        Instant from = Instant.parse("2026-08-20T13:42:00Z");
        Instant to = Instant.parse("2026-08-22T12:00:00Z");
        var custom = new AnalyticsRange(RangePreset.CUSTOM, from, to, "UTC",
                false, null, null);
        when(repository.trend(TrendMetric.REPORTS_SUBMITTED, from, to, Granularity.DAY))
                .thenReturn(List.of(new TrendBucket(Instant.parse("2026-08-20T00:00:00Z"), 4),
                        new TrendBucket(Instant.parse("2026-08-22T00:00:00Z"), 1)));

        AnalyticsTrend result = service.trend(custom, TrendMetric.REPORTS_SUBMITTED,
                Granularity.DAY, "correlation");

        assertThat(result.points()).extracting(AnalyticsContracts.TrendPoint::bucketStart)
                .containsExactly(from, Instant.parse("2026-08-21T00:00:00Z"),
                        Instant.parse("2026-08-22T00:00:00Z"));
        assertThat(result.points()).extracting(AnalyticsContracts.TrendPoint::bucketEnd)
                .containsExactly(Instant.parse("2026-08-21T00:00:00Z"),
                        Instant.parse("2026-08-22T00:00:00Z"), to);
        assertThat(result.points()).extracting(AnalyticsContracts.TrendPoint::value)
                .containsExactly(BigDecimal.valueOf(4), BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void ownerTrendUsesTheSameClippedZeroFilledContract() {
        Instant from = Instant.parse("2026-08-20T13:42:00Z");
        Instant to = Instant.parse("2026-08-22T12:00:00Z");
        var custom = new AnalyticsRange(RangePreset.CUSTOM, from, to, "UTC",
                false, null, null);
        var ownerTrend = new OwnerTrend("ORDERS_CREATED",
                new OwnerRange(from, to, "UTC", null, null), "DAY",
                List.of(new OwnerTrendPoint(Instant.parse("2026-08-20T00:00:00Z"), null, 4),
                        new OwnerTrendPoint(Instant.parse("2026-08-22T00:00:00Z"), null, 1)),
                null, NOW);
        when(owners.orderTrend("ORDERS_CREATED", from, to, Granularity.DAY, "correlation"))
                .thenReturn(OwnerResult.available(ownerTrend));

        AnalyticsTrend result = service.trend(custom, TrendMetric.ORDERS_CREATED,
                Granularity.DAY, "correlation");

        assertThat(result.points()).extracting(AnalyticsContracts.TrendPoint::bucketStart)
                .containsExactly(from, Instant.parse("2026-08-21T00:00:00Z"),
                        Instant.parse("2026-08-22T00:00:00Z"));
        assertThat(result.points()).extracting(AnalyticsContracts.TrendPoint::value)
                .containsExactly(BigDecimal.valueOf(4), BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void systemHealthAndSnapshotsStartConcurrentlyWithinTheOperationsSection() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id(),
                AdminPermission.SYSTEM_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Order unavailable."));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Payment unavailable."));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.unavailable("Catalog unavailable."));
        CountDownLatch started = new CountDownLatch(2);
        when(systems.health()).thenAnswer(ignored -> afterBothStarted(started, List.of()));
        when(systems.snapshots()).thenAnswer(ignored -> afterBothStarted(started, List.of()));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AdminAnalyticsService concurrentService = new AdminAnalyticsService(auth, authorization,
                    repository, owners, systems, new AnalyticsComparisonCalculator(),
                    Clock.fixed(NOW, ZoneOffset.UTC), executor);

            AdminAnalyticsOverview result = concurrentService.overview(range(false), "correlation");

            assertThat(result.operations().status()).isEqualTo(Availability.AVAILABLE);
            assertThat(started.getCount()).isZero();
        }
    }

    @Test
    void blockedOperationsSourcesReturnAnUnavailableSectionAtTheFacadeDeadline() {
        when(authorization.accessFor(user)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("OPERATIONS_ADMIN"), List.of(AdminPermission.ANALYTICS_READ.id(),
                AdminPermission.SYSTEM_READ.id())));
        stubLocal();
        when(owners.orderSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Order unavailable."));
        when(owners.paymentSummary(any(), any(), eq(false), eq(false), anyString()))
                .thenReturn(OwnerResult.unavailable("Payment unavailable."));
        when(owners.productSummary(any(), any(), anyString()))
                .thenReturn(OwnerResult.unavailable("Catalog unavailable."));
        CountDownLatch release = new CountDownLatch(1);
        when(systems.health()).thenAnswer(ignored -> afterRelease(release));
        when(systems.snapshots()).thenAnswer(ignored -> afterRelease(release));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AdminAnalyticsService boundedService = new AdminAnalyticsService(auth, authorization,
                    repository, owners, systems, new AnalyticsComparisonCalculator(),
                    Clock.fixed(NOW, ZoneOffset.UTC), executor);
            long started = System.nanoTime();

            AdminAnalyticsOverview result = boundedService.overview(range(false), "correlation");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(result.operations().status()).isEqualTo(Availability.UNAVAILABLE);
            assertThat(result.operations().safeMessage()).contains("bounded timeout");
            assertThat(elapsedMillis).isLessThan(4_500L);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    private <T> T afterBothStarted(CountDownLatch started, T result) throws InterruptedException {
        started.countDown();
        if (!started.await(1, TimeUnit.SECONDS)) {
            throw new AssertionError("Health and snapshot reads did not overlap.");
        }
        return result;
    }

    private <T> List<T> afterRelease(CountDownLatch release) throws InterruptedException {
        release.await(10, TimeUnit.SECONDS);
        return List.of();
    }

    private void stubLocal() {
        when(repository.marketplaceWindow(any(), any())).thenReturn(new MarketplaceWindow(2, 1));
        when(repository.marketplaceBacklog()).thenReturn(new MarketplaceBacklog(12, 4));
        when(repository.moderationWindow(any(), any()))
                .thenReturn(new ModerationWindow(1, 1, 0, BigDecimal.TEN));
        when(repository.moderationBacklog(any())).thenReturn(new ModerationBacklog(2, 1, 100L));
        when(repository.trustWindow(any(), any()))
                .thenReturn(new TrustWindow(3, 1, 1, 1, 0, 1, 1, 6, 2, 1, 1));
        when(repository.trustBacklog(any()))
                .thenReturn(new TrustBacklog(3, 2, 200L, 2, 1, 1, 1, 300L,
                        2, 1, 1, 400L));
        lenient().when(repository.supportWindow(any(), any()))
                .thenReturn(new SupportWindow(2, 1, BigDecimal.valueOf(300), BigDecimal.valueOf(60)));
        lenient().when(repository.supportBacklog(any())).thenReturn(new SupportBacklog(3, 1, 2, 500L));
        lenient().when(repository.governance(any()))
                .thenReturn(new GovernanceBacklog(1, 0, 0, 1));
        lenient().when(repository.supportCategories(any(), any())).thenReturn(List.of());
        when(repository.enforcementByTargetAndAction(any(), any())).thenReturn(List.of());
        when(repository.enforcementByTargetAndScope(any(), any(), any())).thenReturn(List.of());
    }

    private AnalyticsRange range(boolean compare) {
        return new AnalyticsRange(RangePreset.CUSTOM, NOW.minusSeconds(86400), NOW, "UTC",
                compare, compare ? NOW.minusSeconds(172800) : null,
                compare ? NOW.minusSeconds(86400) : null);
    }

    private OrderSummary orderSummary() {
        var orders = new OrderWindow(4, 4, 1, 2,
                List.of(new MoneyAmount("USD", BigDecimal.valueOf(100))));
        var disputes = new DisputeWindow(1, 1, 1, 0, 0, 0);
        return new OrderSummary(null, new OrderOwnerWindow(orders, disputes), null,
                new DisputeBacklog(1, 1, NOW.minusSeconds(100), 100L), NOW);
    }

    private PaymentSummary paymentSummary() {
        var payments = new PaymentOutcomeWindow(3, 1, BigDecimal.valueOf(75),
                List.of(new MoneyAmount("USD", BigDecimal.valueOf(90))));
        var refunds = new RefundWindow(2, 1, 1, 1, 1, 1, 0, 0, 1, 0,
                List.of(new RefundAmountMetric("USD", BigDecimal.valueOf(20),
                        BigDecimal.TEN, BigDecimal.TEN)));
        return new PaymentSummary(null, new PaymentOwnerWindow(payments, refunds), null,
                new RefundBacklog(1, 1, NOW.minusSeconds(100), 100L,
                        List.of(new RefundActiveAmount("USD", BigDecimal.valueOf(20)))), NOW);
    }

    private ProductSummary productSummary() {
        var listings = new ListingStateSummary(10, 1, 1, 5, 1, 0, 0, 1, 0, 1, 2);
        var moderation = new ProductModerationSummary(2, 1, 1, NOW.minusSeconds(100),
                100L, 1, 50L, 1, 0, 1, 0, BigDecimal.ONE);
        return new ProductSummary(null, listings, moderation,
                new CategorySummary(List.of(), List.of()),
                new ListingEnforcementSummary("LISTING", 1, 1, List.of(),
                        List.of(new EnforcementScopeBreakdown("MARKETPLACE_VISIBILITY", 1, 1))), NOW);
    }
}
