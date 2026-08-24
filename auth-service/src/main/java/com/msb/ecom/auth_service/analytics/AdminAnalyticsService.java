package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.system.SystemContracts;
import com.msb.ecom.auth_service.system.SystemOperationsClient;
import com.msb.ecom.auth_service.system.SystemOperationsSummaryAssembler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.*;
import static com.msb.ecom.auth_service.analytics.AuthAnalyticsRepository.*;
import static com.msb.ecom.auth_service.analytics.OwnerAnalyticsContracts.*;

@Service
public class AdminAnalyticsService {
    private final AuthService auth;
    private final AdminAuthorizationService authorization;
    private final AuthAnalyticsRepository repository;
    private final AnalyticsOwnerClient owners;
    private final SystemOperationsClient systems;
    private final AnalyticsComparisonCalculator comparisons;
    private final Clock clock;
    private final Executor executor;

    public AdminAnalyticsService(AuthService auth, AdminAuthorizationService authorization,
            AuthAnalyticsRepository repository, AnalyticsOwnerClient owners,
            SystemOperationsClient systems, AnalyticsComparisonCalculator comparisons,
            Clock clock, @Qualifier("analyticsExecutor") Executor executor) {
        this.auth = auth;
        this.authorization = authorization;
        this.repository = repository;
        this.owners = owners;
        this.systems = systems;
        this.comparisons = comparisons;
        this.clock = clock;
        this.executor = executor;
    }

    public AdminAnalyticsOverview overview(AnalyticsRange range, String correlationId) {
        User actor = authorized();
        AdminAuthorizationService.AdminAccessSnapshot access = authorization.accessFor(actor);
        boolean finance = access.has(AdminPermission.FINANCE_READ);
        boolean operations = access.has(AdminPermission.SYSTEM_READ);
        boolean governance = access.has(AdminPermission.GOVERNANCE_READ);
        Instant generatedAt = clock.instant();

        CompletableFuture<OwnerResult<OrderSummary>> orderFuture = async(
                () -> owners.orderSummary(range.from(), range.to(), range.comparisonEnabled(),
                        finance, correlationId), "Order analytics is temporarily unavailable.");
        CompletableFuture<OwnerResult<PaymentSummary>> paymentFuture = async(
                () -> owners.paymentSummary(range.from(), range.to(), range.comparisonEnabled(),
                        finance, correlationId), "Payment analytics is temporarily unavailable.");
        CompletableFuture<OwnerResult<ProductSummary>> productFuture = async(
                () -> owners.productSummary(range.from(), range.to(), correlationId),
                "Catalog analytics is temporarily unavailable.");
        CompletableFuture<OwnerResult<ProductSummary>> previousProductFuture = range.comparisonEnabled()
                ? async(() -> owners.productSummary(range.comparisonFrom(), range.comparisonTo(),
                        correlationId), "Previous catalog analytics is temporarily unavailable.")
                : null;
        CompletableFuture<AnalyticsSection> operationsFuture = operations
                ? operationsAsync(generatedAt)
                : null;

        OwnerResult<MarketplaceLocal> marketplaceLocal = local(() -> new MarketplaceLocal(
                repository.marketplaceWindow(range.from(), range.to()),
                range.comparisonEnabled() ? repository.marketplaceWindow(
                        range.comparisonFrom(), range.comparisonTo()) : null,
                repository.marketplaceBacklog()),
                "Marketplace identity analytics is temporarily unavailable.");
        OwnerResult<ModerationLocal> moderationLocal = local(() -> new ModerationLocal(
                repository.moderationWindow(range.from(), range.to()),
                range.comparisonEnabled() ? repository.moderationWindow(
                        range.comparisonFrom(), range.comparisonTo()) : null,
                repository.moderationBacklog(generatedAt)),
                "Business moderation analytics is temporarily unavailable.");
        OwnerResult<TrustLocal> trustLocal = local(() -> new TrustLocal(
                repository.trustWindow(range.from(), range.to()),
                range.comparisonEnabled() ? repository.trustWindow(
                        range.comparisonFrom(), range.comparisonTo()) : null,
                repository.trustBacklog(generatedAt),
                repository.enforcementByTargetAndAction(range.from(), range.to()),
                repository.enforcementByTargetAndScope(range.from(), range.to(), generatedAt)),
                "Trust analytics is temporarily unavailable.");
        OwnerResult<SupportLocal> supportLocal = local(() -> new SupportLocal(
                repository.supportWindow(range.from(), range.to()),
                range.comparisonEnabled() ? repository.supportWindow(
                        range.comparisonFrom(), range.comparisonTo()) : null,
                repository.supportBacklog(generatedAt),
                repository.supportCategories(range.from(), range.to())),
                "Support analytics is temporarily unavailable.");
        OwnerResult<GovernanceBacklog> governanceLocal = governance
                ? local(() -> repository.governance(generatedAt),
                        "Governance analytics is temporarily unavailable.")
                : null;

        OwnerResult<OrderSummary> order = orderFuture.join();
        OwnerResult<PaymentSummary> payment = paymentFuture.join();
        OwnerResult<ProductSummary> product = productFuture.join();
        OwnerResult<ProductSummary> previousProduct = previousProductFuture == null
                ? null : previousProductFuture.join();

        AnalyticsSection operationsSection = operations
                ? operationsFuture.join()
                : AnalyticsSection.restricted("System analytics requires admin.system.read.", generatedAt);
        AnalyticsSection governanceSection = governance
                ? governance(governanceLocal, generatedAt)
                : AnalyticsSection.restricted("Governance signals require admin.governance.read.", generatedAt);

        return new AdminAnalyticsOverview(range,
                new Capabilities(finance, operations, governance),
                marketplace(marketplaceLocal, product, previousProduct, order, generatedAt),
                moderation(moderationLocal, product, previousProduct, generatedAt),
                trust(trustLocal, product, previousProduct, generatedAt),
                commerce(order, payment, finance, generatedAt),
                support(supportLocal, generatedAt),
                catalog(product, previousProduct, generatedAt),
                operationsSection, governanceSection, generatedAt);
    }

    public AnalyticsTrend trend(AnalyticsRange range, TrendMetric metric,
            Granularity granularity, String correlationId) {
        authorized();
        Instant generatedAt = clock.instant();
        if (metric == TrendMetric.REPORTS_SUBMITTED
                || metric == TrendMetric.SUPPORT_TICKETS_CREATED) {
            List<RawTrendPoint> raw = repository.trend(metric, range.from(), range.to(), granularity)
                    .stream().map(value -> new RawTrendPoint(value.bucketStart(), value.value())).toList();
            return new AnalyticsTrend(metric, trendLabel(metric), MetricUnit.COUNT, range,
                    granularity, Availability.AVAILABLE, null,
                    normalizeTrend(range, granularity, raw), generatedAt);
        }

        OwnerResult<OwnerTrend> result = switch (metric) {
            case ORDERS_CREATED, DISPUTES_OPENED -> owners.orderTrend(metric.name(), range.from(),
                    range.to(), granularity, correlationId);
            case REFUNDS_SUCCEEDED -> owners.paymentTrend(metric.name(), range.from(), range.to(),
                    granularity, correlationId);
            case LISTINGS_CREATED -> owners.productListingTrend(range.from(), range.to(),
                    granularity, correlationId);
            default -> OwnerResult.unavailable("The requested analytics trend is unavailable.");
        };
        if (!result.available()) {
            return new AnalyticsTrend(metric, trendLabel(metric), MetricUnit.COUNT, range,
                    granularity, Availability.UNAVAILABLE, result.safeMessage(), List.of(), generatedAt);
        }
        List<RawTrendPoint> raw = safe(result.data().points()).stream()
                .map(value -> new RawTrendPoint(value.bucketStart(), value.value())).toList();
        return new AnalyticsTrend(metric, trendLabel(metric), MetricUnit.COUNT, range,
                granularity, Availability.AVAILABLE, null,
                normalizeTrend(range, granularity, raw), generatedAt);
    }

    private AnalyticsSection marketplace(OwnerResult<MarketplaceLocal> local,
            OwnerResult<ProductSummary> product,
            OwnerResult<ProductSummary> previousProduct, OwnerResult<OrderSummary> order,
            Instant generatedAt) {
        List<AnalyticsMetric> metrics = new ArrayList<>();
        if (local.available()) {
            MarketplaceLocal data = local.data();
            metrics.add(comparisons.count("totalUsers", "Marketplace users",
                    data.backlog().totalUsers(), null, null));
            metrics.add(comparisons.count("newUsers", "New users", data.current().newUsers(),
                    data.previous() == null ? null : data.previous().newUsers(), null));
            metrics.add(comparisons.count("activeBusinesses", "Active businesses",
                    data.backlog().activeBusinesses(), null, null));
            metrics.add(comparisons.count("newBusinesses", "New businesses",
                    data.current().newBusinesses(), data.previous() == null ? null
                            : data.previous().newBusinesses(), null));
        }
        if (product.available()) {
            ProductSummary previousData = availableData(previousProduct);
            metrics.add(comparisons.count("activeListings", "Active listings",
                    product.data().listings().activeListings(), null, null));
            metrics.add(comparisons.count("newListings", "New listings",
                    product.data().listings().newListings(), previousData == null ? null
                            : previousData.listings().newListings(), null));
        }
        if (order.available()) {
            OrderOwnerWindow prior = order.data().previous();
            metrics.add(comparisons.count("ordersCreated", "Orders created",
                    order.data().current().orders().ordersCreated(), prior == null ? null
                            : prior.orders().ordersCreated(), null));
            metrics.add(comparisons.count("ordersFullyDelivered", "Fully delivered orders",
                    order.data().current().orders().fullyDeliveredOrders(), prior == null ? null
                            : prior.orders().fullyDeliveredOrders(), null));
        }
        boolean productComplete = product.available()
                && (previousProduct == null || previousProduct.available());
        Availability status = availability(local.available(), productComplete, order.available());
        return new AnalyticsSection(status, messages(local, product, previousProduct, order),
                generatedAt, metrics, List.of());
    }

    private AnalyticsSection moderation(OwnerResult<ModerationLocal> local,
            OwnerResult<ProductSummary> product,
            OwnerResult<ProductSummary> previousProduct, Instant generatedAt) {
        List<AnalyticsMetric> metrics = new ArrayList<>();
        if (local.available()) {
            ModerationLocal data = local.data();
            metrics.add(comparisons.count("pendingBusinessApplications", "Pending business applications",
                    data.backlog().pending(), null, null));
            metrics.add(comparisons.count("unassignedBusinessApplications", "Unassigned business applications",
                    data.backlog().unassigned(), null, null));
            metrics.add(comparisons.duration("oldestPendingBusinessApplicationAge",
                    "Oldest pending business application", data.backlog().oldestSeconds(), null, null));
            metrics.add(comparisons.duration("averageBusinessSubmissionToDecisionTime",
                    "Average business submission-to-decision time",
                    data.current().averageReviewSeconds(), data.previous() == null ? null
                            : data.previous().averageReviewSeconds(), null));
        }
        if (product.available()) {
            ProductModerationSummary data = product.data().moderation();
            ProductSummary prior = availableData(previousProduct);
            metrics.add(comparisons.count("pendingListingModeration", "Open listing moderation cases",
                    data.openCount(), null, null));
            metrics.add(comparisons.count("unassignedListingCases", "Unassigned listing cases",
                    data.unassignedCount(), null, DrillDownKey.UNASSIGNED_LISTING_CASES));
            metrics.add(comparisons.count("assignedListingCases", "Assigned listing cases",
                    data.assignedCount(), null, null));
            metrics.add(comparisons.duration("oldestPendingListingCaseAge",
                    "Oldest pending listing case", data.oldestOpenAgeSeconds(), null, null));
            metrics.add(comparisons.duration("averageListingModerationTime",
                    "Average listing moderation time", data.averageResolutionSeconds(),
                    prior == null ? null : prior.moderation().averageResolutionSeconds(), null));
            metrics.add(comparisons.percent("listingModerationRejectionRate",
                    "Listing moderation rejection rate", asPercent(data.rejectionRate()),
                    prior == null ? null : asPercent(prior.moderation().rejectionRate()), null));
        }
        boolean productComplete = product.available()
                && (previousProduct == null || previousProduct.available());
        return new AnalyticsSection(availability(local.available(), productComplete),
                messages(local, product, previousProduct), generatedAt, metrics, List.of());
    }

    private AnalyticsSection trust(OwnerResult<TrustLocal> local,
            OwnerResult<ProductSummary> product, OwnerResult<ProductSummary> previousProduct,
            Instant generatedAt) {
        List<AnalyticsMetric> metrics = new ArrayList<>();
        List<AnalyticsBreakdownItem> items = new ArrayList<>();
        List<AnalyticsBreakdownItem> scopeItems = new ArrayList<>();
        if (local.available()) {
            TrustLocal data = local.data();
            TrustWindow now = data.current();
            TrustWindow prior = data.previous();
            TrustBacklog queue = data.backlog();
            metrics.add(comparisons.count("reportsSubmitted", "Reports submitted", now.reportsSubmitted(),
                    prior == null ? null : prior.reportsSubmitted(), null));
            metrics.add(comparisons.count("reportsDismissed", "Reports dismissed", now.reportsDismissed(),
                    prior == null ? null : prior.reportsDismissed(), null));
            metrics.add(comparisons.count("reportsReadyForInvestigation", "Reports ready for investigation",
                    now.reportsReady(), prior == null ? null : prior.reportsReady(), null));
            metrics.add(comparisons.count("unresolvedReports", "Current unresolved reports",
                    queue.unresolvedReports(), null, null));
            metrics.add(comparisons.count("unassignedReports", "Current unassigned reports",
                    queue.unassignedReports(), null, DrillDownKey.UNASSIGNED_REPORTS));
            metrics.add(comparisons.duration("oldestUnresolvedReportAge", "Oldest unresolved report",
                    queue.oldestReportSeconds(), null, null));
            metrics.add(comparisons.count("investigationCasesOpened", "Investigation cases opened",
                    now.casesOpened(), prior == null ? null : prior.casesOpened(), null));
            metrics.add(comparisons.count("openInvestigationCases", "Current open investigation cases",
                    queue.openCases(), null, null));
            metrics.add(comparisons.count("casesUnderInvestigation", "Cases under investigation",
                    queue.casesUnderInvestigation(), null, null));
            metrics.add(comparisons.count("casesReadyForAction", "Cases ready for action",
                    queue.casesReady(), null, DrillDownKey.READY_FOR_ACTION_CASES));
            metrics.add(comparisons.count("casesClosedNoAction", "Cases closed without action",
                    now.casesClosedNoAction(), prior == null ? null : prior.casesClosedNoAction(), null));
            metrics.add(comparisons.count("casesClosedActioned", "Cases closed with action",
                    now.casesClosedActioned(), prior == null ? null : prior.casesClosedActioned(), null));
            metrics.add(comparisons.count("unassignedCases", "Current unassigned cases",
                    queue.unassignedCases(), null, null));
            metrics.add(comparisons.duration("oldestOpenCaseAge", "Oldest open investigation case",
                    queue.oldestCaseSeconds(), null, null));
            metrics.add(comparisons.count("userAndBusinessEnforcementActionsCreated",
                    "User and business enforcement actions created", now.enforcementCreated(),
                    prior == null ? null : prior.enforcementCreated(), null));
            metrics.add(comparisons.count("activeUserAndBusinessEnforcementActions",
                    "Current active user and business enforcement actions",
                    queue.activeEnforcement(), null, null));
            metrics.add(comparisons.count("appealsSubmitted", "Appeals submitted", now.appealsSubmitted(),
                    prior == null ? null : prior.appealsSubmitted(), null));
            long finalized = finalizedAppeals(now);
            Long priorFinalized = prior == null ? null : finalizedAppeals(prior);
            metrics.add(comparisons.count("appealsFinalized", "Finalized appeals", finalized,
                    priorFinalized, null));
            metrics.add(comparisons.count("appealsUpheld", "Upheld", now.appealsUpheld(),
                    prior == null ? null : prior.appealsUpheld(), null));
            metrics.add(comparisons.count("appealsModified", "Modified", now.appealsModified(),
                    prior == null ? null : prior.appealsModified(), null));
            metrics.add(comparisons.count("appealsRevoked", "Revoked", now.appealsRevoked(),
                    prior == null ? null : prior.appealsRevoked(), null));
            metrics.add(comparisons.percent("appealAdjustmentRate", "Appeals changed/reversed",
                    rate(now.appealsModified() + now.appealsRevoked(), finalized),
                    prior == null ? null : rate(prior.appealsModified() + prior.appealsRevoked(),
                            priorFinalized), null));
            metrics.add(comparisons.count("pendingAppeals", "Current pending appeals",
                    queue.pendingAppeals(), null, null));
            metrics.add(comparisons.count("unassignedAppeals", "Current unassigned appeals",
                    queue.unassignedAppeals(), null, null));
            metrics.add(comparisons.duration("oldestPendingAppealAge", "Oldest pending appeal",
                    queue.oldestAppealSeconds(), null, null));
            data.enforcement().stream()
                    .map(value -> item(value.key(), value.label(), value.value(), null, null))
                    .forEach(items::add);
            data.enforcementScopes().stream()
                    .map(value -> item(value.key(), value.label(), value.createdInRange(),
                            value.currentActive(), null))
                    .forEach(scopeItems::add);
        }

        ProductSummary previousData = availableData(previousProduct);
        if (product.available()) {
            metrics.add(comparisons.count("listingEnforcementActionsCreated",
                    "Listing enforcement actions created",
                    product.data().listingEnforcement().createdInRange(),
                    previousData == null ? null
                            : previousData.listingEnforcement().createdInRange(), null));
            metrics.add(comparisons.count("activeListingEnforcementActions",
                    "Current active listing enforcement actions",
                    product.data().listingEnforcement().currentActive(), null, null));
            for (EnforcementActionBreakdown value : product.data().listingEnforcement().byActionType()) {
                items.add(item("LISTING:" + value.actionType(), "LISTING " + value.actionType(),
                        value.createdInRange(), value.currentActive(), null));
            }
            for (EnforcementScopeBreakdown value : safe(
                    product.data().listingEnforcement().byScope())) {
                scopeItems.add(item("LISTING:" + value.scope(), "LISTING " + value.scope(),
                        value.createdInRange(), value.currentActive(), null));
            }
        }
        List<AnalyticsBreakdown> breakdowns = local.available() || product.available()
                ? List.of(
                        new AnalyticsBreakdown("enforcementByTargetAndAction",
                                "Enforcement by target and action", MetricUnit.COUNT,
                                "Created in selected range", "Current active", items),
                        new AnalyticsBreakdown("enforcementByTargetAndScope",
                                "Enforcement by target and scope", MetricUnit.COUNT,
                                "Created in selected range", "Current active", scopeItems))
                : List.of();
        boolean productComplete = product.available()
                && (previousProduct == null || previousProduct.available());
        return new AnalyticsSection(availability(local.available(), productComplete),
                messages(local, product, previousProduct), generatedAt, metrics, breakdowns);
    }

    private AnalyticsSection commerce(OwnerResult<OrderSummary> order,
            OwnerResult<PaymentSummary> payment, boolean finance, Instant generatedAt) {
        if (!order.available() && !payment.available()) {
            return AnalyticsSection.unavailable(messages(order, payment), generatedAt);
        }
        List<AnalyticsMetric> metrics = new ArrayList<>();
        List<AnalyticsBreakdown> breakdowns = new ArrayList<>();
        if (order.available()) {
            OrderOwnerWindow current = order.data().current();
            OrderOwnerWindow previous = order.data().previous();
            metrics.add(comparisons.count("ordersCreated", "Orders created",
                    current.orders().ordersCreated(), previous == null ? null
                            : previous.orders().ordersCreated(), null));
            metrics.add(comparisons.count("ordersConfirmed", "Orders confirmed",
                    current.orders().ordersConfirmed(), previous == null ? null
                            : previous.orders().ordersConfirmed(), null));
            metrics.add(comparisons.count("ordersFullyDelivered", "Fully delivered orders",
                    current.orders().fullyDeliveredOrders(), previous == null ? null
                            : previous.orders().fullyDeliveredOrders(), null));
            metrics.add(comparisons.count("ordersCancelled", "Created orders now cancelled",
                    current.orders().currentCancelledOrders(), previous == null ? null
                            : previous.orders().currentCancelledOrders(), null));
            metrics.add(comparisons.percent("cancellationRate", "Order cancellation rate",
                    rate(current.orders().currentCancelledOrders(), current.orders().ordersCreated()),
                    previous == null ? null : rate(previous.orders().currentCancelledOrders(),
                            previous.orders().ordersCreated()), null));
            metrics.add(comparisons.count("disputesOpened", "Disputes opened",
                    current.disputes().disputesOpened(), previous == null ? null
                            : previous.disputes().disputesOpened(), null));
            metrics.add(comparisons.count("disputesResolved", "Disputes resolved",
                    current.disputes().disputesResolved(), previous == null ? null
                            : previous.disputes().disputesResolved(), null));
            DisputeBacklog queue = order.data().currentDisputeBacklog();
            metrics.add(comparisons.count("openDisputes", "Current open disputes",
                    queue.openCount(), null, null));
            metrics.add(comparisons.count("unassignedDisputes", "Current unassigned disputes",
                    queue.unassignedCount(), null, null));
            metrics.add(comparisons.duration("oldestOpenDisputeAge", "Oldest open dispute",
                    queue.oldestOpenAgeSeconds(), null, null));
            metrics.add(comparisons.count("disputesResolvedNoAction", "Resolved without action",
                    current.disputes().resolvedNoAction(), previous == null ? null
                            : previous.disputes().resolvedNoAction(), null));
            metrics.add(comparisons.count("returnsApproved", "Returns approved",
                    current.disputes().returnsApproved(), previous == null ? null
                            : previous.disputes().returnsApproved(), null));
            metrics.add(comparisons.count("refundsRecommended", "Refunds recommended",
                    current.disputes().refundsRecommended(), previous == null ? null
                            : previous.disputes().refundsRecommended(), null));
            metrics.add(comparisons.count("partialRefundsRecommended", "Partial refunds recommended",
                    current.disputes().partialRefundsRecommended(), previous == null ? null
                            : previous.disputes().partialRefundsRecommended(), null));
            if (finance && current.orders().grossCreatedAmounts() != null) {
                breakdowns.add(moneyBreakdown("grossOrderAmountsCreated",
                        "Gross order amounts created", current.orders().grossCreatedAmounts(),
                        previous == null ? List.of() : previous.orders().grossCreatedAmounts()));
            }
        }
        if (payment.available()) {
            PaymentOwnerWindow current = payment.data().current();
            PaymentOwnerWindow previous = payment.data().previous();
            metrics.add(comparisons.count("paymentsSucceeded", "Payments succeeded",
                    current.payments().paymentsSucceeded(), previous == null ? null
                            : previous.payments().paymentsSucceeded(), null));
            metrics.add(comparisons.count("paymentsFailed", "Payments failed",
                    current.payments().paymentsFailed(), previous == null ? null
                            : previous.payments().paymentsFailed(), null));
            metrics.add(comparisons.percent("paymentSuccessRate", "Payment success rate",
                    current.payments().paymentSuccessRatePercent(), previous == null ? null
                            : previous.payments().paymentSuccessRatePercent(), null));
            metrics.add(comparisons.count("refundsRequested", "Refunds requested",
                    current.refunds().refundsRequested(), previous == null ? null
                            : previous.refunds().refundsRequested(), null));
            metrics.add(comparisons.count("refundsSucceeded", "Refunds succeeded",
                    current.refunds().refundsSucceeded(), previous == null ? null
                            : previous.refunds().refundsSucceeded(), null));
            metrics.add(comparisons.count("refundsFailed", "Refunds failed",
                    current.refunds().refundsFailed(), previous == null ? null
                            : previous.refunds().refundsFailed(), null));
            metrics.add(comparisons.count("fullRefundsRequested", "Full refunds requested",
                    current.refunds().fullRefundsRequested(), previous == null ? null
                            : previous.refunds().fullRefundsRequested(), null));
            metrics.add(comparisons.count("partialRefundsRequested", "Partial refunds requested",
                    current.refunds().partialRefundsRequested(), previous == null ? null
                            : previous.refunds().partialRefundsRequested(), null));
            metrics.add(comparisons.count("fullRefundsSucceeded", "Full refunds succeeded",
                    current.refunds().fullRefundsSucceeded(), previous == null ? null
                            : previous.refunds().fullRefundsSucceeded(), null));
            metrics.add(comparisons.count("partialRefundsSucceeded", "Partial refunds succeeded",
                    current.refunds().partialRefundsSucceeded(), previous == null ? null
                            : previous.refunds().partialRefundsSucceeded(), null));
            metrics.add(comparisons.count("fullRefundsFailed", "Full refunds failed",
                    current.refunds().fullRefundsFailed(), previous == null ? null
                            : previous.refunds().fullRefundsFailed(), null));
            metrics.add(comparisons.count("partialRefundsFailed", "Partial refunds failed",
                    current.refunds().partialRefundsFailed(), previous == null ? null
                            : previous.refunds().partialRefundsFailed(), null));
            metrics.add(comparisons.count("returnRefundsSucceeded", "Return refunds succeeded",
                    current.refunds().returnRefundsSucceeded(), previous == null ? null
                            : previous.refunds().returnRefundsSucceeded(), null));
            metrics.add(comparisons.percent("refundFailureRate", "Refund failure rate",
                    rate(current.refunds().refundsFailed(), current.refunds().refundsSucceeded()
                            + current.refunds().refundsFailed()), previous == null ? null
                            : rate(previous.refunds().refundsFailed(),
                            previous.refunds().refundsSucceeded() + previous.refunds().refundsFailed()), null));
            RefundBacklog queue = payment.data().currentRefundBacklog();
            metrics.add(comparisons.count("refundsPending", "Current pending refunds",
                    queue.pendingCount(), null, null));
            metrics.add(comparisons.count("refundsProcessing", "Current processing refunds",
                    queue.processingCount(), null, DrillDownKey.PROCESSING_REFUNDS));
            metrics.add(comparisons.duration("oldestActiveRefundAge", "Oldest active refund",
                    queue.oldestActiveAgeSeconds(), null, null));
            if (finance) addPaymentMoney(breakdowns, current, previous, queue);
        }
        Availability status = order.available() && payment.available()
                ? Availability.AVAILABLE : Availability.DEGRADED;
        return new AnalyticsSection(status, messages(order, payment), generatedAt, metrics, breakdowns);
    }

    private AnalyticsSection support(OwnerResult<SupportLocal> local, Instant generatedAt) {
        if (!local.available()) {
            return AnalyticsSection.unavailable(local.safeMessage(), generatedAt);
        }
        SupportWindow now = local.data().current();
        SupportWindow prior = local.data().previous();
        SupportBacklog queue = local.data().backlog();
        List<AnalyticsMetric> metrics = List.of(
                comparisons.count("ticketsCreated", "Support tickets created", now.created(),
                        prior == null ? null : prior.created(), null),
                comparisons.count("openTickets", "Current open support tickets", queue.open(),
                        null, null),
                comparisons.count("waitingForUser", "Waiting for user", queue.waitingForUser(),
                        null, DrillDownKey.WAITING_FOR_USER_SUPPORT_TICKETS),
                comparisons.count("resolvedTickets", "Support tickets resolved", now.resolved(),
                        prior == null ? null : prior.resolved(), null),
                comparisons.count("unassignedTickets", "Current unassigned support tickets",
                        queue.unassigned(), null, null),
                comparisons.duration("oldestOpenTicketAge", "Oldest open support ticket",
                        queue.oldestSeconds(), null, null),
                comparisons.duration("averageFirstResponseTime", "Average first response time",
                        now.averageFirstResponseSeconds(), prior == null ? null
                                : prior.averageFirstResponseSeconds(), null),
                comparisons.duration("averageResolutionTime", "Average resolution time",
                        now.averageResolutionSeconds(), prior == null ? null
                                : prior.averageResolutionSeconds(), null));
        List<AnalyticsBreakdownItem> items = local.data().categories().stream()
                .map(value -> item(value.key(), humanize(value.label()), value.value(), null, null)).toList();
        return new AnalyticsSection(Availability.AVAILABLE, null, generatedAt, metrics,
                List.of(new AnalyticsBreakdown("ticketsByCategory", "Tickets by category",
                        MetricUnit.COUNT, "Tickets created", null, items)));
    }

    private AnalyticsSection catalog(OwnerResult<ProductSummary> product,
            OwnerResult<ProductSummary> previousProduct, Instant generatedAt) {
        if (!product.available()) {
            return AnalyticsSection.unavailable(product.safeMessage(), generatedAt);
        }
        ListingStateSummary now = product.data().listings();
        ProductSummary prior = availableData(previousProduct);
        List<AnalyticsMetric> metrics = List.of(
                comparisons.count("activeListings", "Active listings", now.activeListings(), null, null),
                comparisons.count("draftListings", "Draft listings", now.draftListings(), null, null),
                comparisons.count("pendingListings", "Pending-review listings",
                        now.pendingReviewListings(), null, null),
                comparisons.count("removedListings", "Admin-removed listings",
                        now.removedByAdminListings(), null, null),
                comparisons.count("activeListingEnforcementActions",
                        "Current active listing enforcement actions",
                        product.data().listingEnforcement().currentActive(), null, null),
                comparisons.count("newListings", "New listings", now.newListings(),
                        prior == null ? null : prior.listings().newListings(), null));
        List<AnalyticsBreakdown> breakdowns = List.of(
                categoryBreakdown("topCategoriesCurrent", "Top categories by current listings",
                        "Current listings", product.data().categories().topByCurrentListings()),
                categoryBreakdown("topCategoriesNew", "Top categories by new listings",
                        "New listings", product.data().categories().topByNewListings()));
        boolean productComplete = previousProduct == null || previousProduct.available();
        return new AnalyticsSection(productComplete ? Availability.AVAILABLE : Availability.DEGRADED,
                messages(product, previousProduct), generatedAt, metrics, breakdowns);
    }

    private CompletableFuture<AnalyticsSection> operationsAsync(Instant generatedAt) {
        CompletableFuture<List<SystemContracts.ServiceHealth>> health =
                CompletableFuture.supplyAsync(systems::health, executor);
        CompletableFuture<List<SystemContracts.SourceSnapshot>> snapshots =
                CompletableFuture.supplyAsync(systems::snapshots, executor);
        return health.thenCombine(snapshots,
                        (healthResults, snapshotResults) -> operations(
                                healthResults, snapshotResults, generatedAt))
                .completeOnTimeout(AnalyticsSection.unavailable(
                        "Operational analytics exceeded the bounded timeout.", generatedAt),
                        3, TimeUnit.SECONDS)
                .exceptionally(error -> AnalyticsSection.unavailable(
                        "Operational analytics is temporarily unavailable.", generatedAt));
    }

    private AnalyticsSection operations(List<SystemContracts.ServiceHealth> health,
            List<SystemContracts.SourceSnapshot> sources, Instant generatedAt) {
        SystemOperationsSummaryAssembler.Signals signals =
                SystemOperationsSummaryAssembler.assemble(health, sources);
        List<AnalyticsMetric> metrics = List.of(
                comparisons.count("servicesHealthy", "Services healthy",
                        signals.healthyServices(), null, null),
                comparisons.count("servicesDegraded", "Services degraded",
                        signals.degradedServices(), null, null),
                comparisons.count("servicesUnavailable", "Services unavailable",
                        signals.unavailableServices(), null, null),
                comparisons.count("failedJobs", "Failed jobs", signals.failedJobs(),
                        null, DrillDownKey.FAILED_JOBS),
                comparisons.count("failedOutboxEvents", "Failed outbox events",
                        signals.failedOutboxEvents(),
                        null, DrillDownKey.FAILED_OUTBOX_EVENTS),
                comparisons.count("deadLetterEvents", "Dead-letter events", signals.deadLetterEvents(),
                        null, DrillDownKey.DEAD_LETTER_OUTBOX_EVENTS),
                comparisons.count("reconciliationIssues", "Reconciliation issues",
                        signals.reconciliationIssues(),
                        null, DrillDownKey.RECONCILIATION_ISSUES),
                comparisons.count("inventoryIssues", "Inventory issues", signals.inventoryIssues(),
                        null, DrillDownKey.INVENTORY_ISSUES),
                comparisons.count("searchIndexFailures", "Search index failures",
                        signals.searchIndexFailures(),
                        null, DrillDownKey.SEARCH_INDEX_FAILURES));
        boolean partial = signals.hasUnavailableSource();
        return new AnalyticsSection(partial ? Availability.DEGRADED : Availability.AVAILABLE,
                partial ? "One or more operational sources are temporarily unavailable." : null,
                generatedAt, metrics, List.of());
    }

    private AnalyticsSection governance(OwnerResult<GovernanceBacklog> local, Instant generatedAt) {
        if (!local.available()) {
            return AnalyticsSection.unavailable(local.safeMessage(), generatedAt);
        }
        GovernanceBacklog data = local.data();
        return new AnalyticsSection(Availability.AVAILABLE, null, generatedAt, List.of(
                comparisons.count("pendingApprovals", "Pending sensitive-action approvals",
                        data.pendingApprovals(), null, DrillDownKey.PENDING_GOVERNANCE_APPROVALS),
                comparisons.count("expiredApprovals", "Expired approvals", data.expiredApprovals(), null, null),
                comparisons.count("failedSensitiveActionExecutions", "Failed sensitive-action executions",
                        data.failedExecutions(), null, null),
                comparisons.count("temporaryElevationsActive", "Active temporary elevations",
                        data.activeTemporaryElevations(), null, DrillDownKey.ACTIVE_TEMPORARY_ELEVATIONS)),
                List.of());
    }

    private User authorized() {
        User user = auth.ensureUserEntity();
        authorization.requirePermission(user, AdminPermission.ANALYTICS_READ);
        return user;
    }

    private <T> CompletableFuture<OwnerResult<T>> async(Supplier<OwnerResult<T>> supplier,
            String timeoutMessage) {
        return CompletableFuture.supplyAsync(supplier, executor)
                .completeOnTimeout(OwnerResult.unavailable(timeoutMessage), 3, TimeUnit.SECONDS)
                .exceptionally(error -> OwnerResult.unavailable(timeoutMessage));
    }

    /** Keeps one Auth-owned domain query failure from erasing unrelated dashboard sections. */
    private <T> OwnerResult<T> local(Supplier<T> supplier, String failureMessage) {
        try {
            return OwnerResult.available(Objects.requireNonNull(supplier.get()));
        } catch (RuntimeException error) {
            return OwnerResult.unavailable(failureMessage);
        }
    }

    private Availability availability(boolean... sources) {
        int available = 0;
        for (boolean source : sources) if (source) available++;
        if (available == 0) return Availability.UNAVAILABLE;
        return available == sources.length ? Availability.AVAILABLE : Availability.DEGRADED;
    }

    private <T> T availableData(OwnerResult<T> result) {
        return result != null && result.available() ? result.data() : null;
    }

    @SafeVarargs
    private final String messages(OwnerResult<?>... results) {
        return java.util.Arrays.stream(results).filter(Objects::nonNull)
                .map(OwnerResult::safeMessage).filter(Objects::nonNull)
                .reduce((left, right) -> left + " " + right).orElse(null);
    }

    private BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private long finalizedAppeals(TrustWindow value) {
        return value.appealsUpheld() + value.appealsModified() + value.appealsRevoked();
    }

    private BigDecimal asPercent(BigDecimal fraction) {
        return fraction == null ? null : fraction.multiply(BigDecimal.valueOf(100));
    }

    private AnalyticsBreakdown categoryBreakdown(String key, String label, String primaryLabel,
            List<CategoryCount> categories) {
        List<AnalyticsBreakdownItem> items = safe(categories).stream()
                .map(value -> item(value.categoryId(), value.categoryName(), value.listingCount(),
                        null, null)).toList();
        return new AnalyticsBreakdown(key, label, MetricUnit.COUNT, primaryLabel, null, items);
    }

    private AnalyticsBreakdown moneyBreakdown(String key, String label,
            List<MoneyAmount> current, List<MoneyAmount> previous) {
        List<MoneyAmount> prior = safe(previous);
        List<AnalyticsBreakdownItem> items = safe(current).stream()
                .map(value -> item(value.currency(), value.currency(), value.amount(),
                        prior.stream().filter(candidate -> value.currency().equals(candidate.currency()))
                                .map(MoneyAmount::amount).findFirst().orElse(null), null)).toList();
        return new AnalyticsBreakdown(key, label, MetricUnit.MONEY,
                "Selected period", "Previous period", items);
    }

    private void addPaymentMoney(List<AnalyticsBreakdown> breakdowns, PaymentOwnerWindow current,
            PaymentOwnerWindow previous, RefundBacklog backlog) {
        breakdowns.add(moneyBreakdown("succeededPaymentAmounts", "Succeeded payment amounts",
                current.payments().succeededPaymentAmounts(), previous == null ? List.of()
                        : previous.payments().succeededPaymentAmounts()));
        List<AnalyticsBreakdownItem> refunds = safe(current.refunds().amountMetrics()).stream()
                .map(value -> item(value.currency(), value.currency(), value.succeededAmount(),
                        value.failedAmount(), null)).toList();
        breakdowns.add(new AnalyticsBreakdown("refundAmounts", "Refund succeeded / failed amounts",
                MetricUnit.MONEY, "Succeeded amount", "Failed amount", refunds));
        List<AnalyticsBreakdownItem> active = safe(backlog.activeAmounts()).stream()
                .map(value -> item(value.currency(), value.currency(), value.amount(), null, null)).toList();
        breakdowns.add(new AnalyticsBreakdown("activeRefundAmounts", "Pending and processing refund amounts",
                MetricUnit.MONEY, "Pending and processing amount", null, active));
    }

    private AnalyticsBreakdownItem item(String key, String label, Number value,
            Number secondary, DrillDownKey drillDown) {
        return new AnalyticsBreakdownItem(key, label, decimal(value), decimal(secondary), drillDown);
    }

    private BigDecimal decimal(Number value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private String humanize(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String trendLabel(TrendMetric metric) {
        return switch (metric) {
            case REPORTS_SUBMITTED -> "Reports submitted";
            case ORDERS_CREATED -> "Orders created";
            case DISPUTES_OPENED -> "Disputes opened";
            case REFUNDS_SUCCEEDED -> "Refunds succeeded";
            case SUPPORT_TICKETS_CREATED -> "Support tickets created";
            case LISTINGS_CREATED -> "Listings created";
        };
    }

    private List<TrendPoint> normalizeTrend(AnalyticsRange range, Granularity granularity,
            List<RawTrendPoint> raw) {
        Map<Instant, Long> values = new HashMap<>();
        for (RawTrendPoint value : raw) {
            values.merge(floorBucket(value.bucketStart(), granularity), value.value(), Long::sum);
        }
        List<TrendPoint> points = new ArrayList<>();
        Instant cursor = floorBucket(range.from(), granularity);
        while (cursor.isBefore(range.to())) {
            Instant next = advanceBucket(cursor, granularity);
            Instant pointFrom = cursor.isBefore(range.from()) ? range.from() : cursor;
            Instant pointTo = next.isAfter(range.to()) ? range.to() : next;
            points.add(new TrendPoint(pointFrom, pointTo,
                    BigDecimal.valueOf(values.getOrDefault(cursor, 0L))));
            cursor = next;
        }
        return List.copyOf(points);
    }

    private Instant floorBucket(Instant value, Granularity granularity) {
        ZonedDateTime utc = value.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case HOUR -> utc.truncatedTo(ChronoUnit.HOURS).toInstant();
            case DAY -> utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            case WEEK -> utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    private Instant advanceBucket(Instant value, Granularity granularity) {
        return switch (granularity) {
            case HOUR -> value.plus(1, ChronoUnit.HOURS);
            case DAY -> value.plus(1, ChronoUnit.DAYS);
            case WEEK -> value.plus(7, ChronoUnit.DAYS);
        };
    }

    private record MarketplaceLocal(MarketplaceWindow current, MarketplaceWindow previous,
            MarketplaceBacklog backlog) { }
    private record ModerationLocal(ModerationWindow current, ModerationWindow previous,
            ModerationBacklog backlog) { }
    private record TrustLocal(TrustWindow current, TrustWindow previous, TrustBacklog backlog,
            List<NamedCount> enforcement, List<ScopeCount> enforcementScopes) { }
    private record SupportLocal(SupportWindow current, SupportWindow previous,
            SupportBacklog backlog, List<NamedCount> categories) { }
    private record RawTrendPoint(Instant bucketStart, long value) { }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
