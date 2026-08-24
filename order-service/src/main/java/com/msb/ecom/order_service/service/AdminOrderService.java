package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProcessingProperties;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.dto.AdminOrderContracts;
import com.msb.ecom.order_service.dto.AdminOrderContracts.*;
import com.msb.ecom.order_service.model.AdminOrderException;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.repository.AdminOrderRepository;
import com.msb.ecom.order_service.repository.AdminOrderRepository.*;
import com.msb.ecom.order_service.security.AdminOrderPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminOrderService {
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Set<String> ORDER_STATUSES = Set.of("CONFIRMED", "CANCELLATION_REQUESTED", "CANCELLED");
    private static final Set<String> PAYMENT_STATUSES = Set.of("SUCCEEDED");
    private static final Set<String> FULFILLMENT_STATUSES = Set.of(
            "PENDING_ACCEPTANCE", "ACCEPTED", "PROCESSING", "SHIPPED", "DELIVERED");
    private static final Set<String> SORTS = Set.of(
            "createdAt,desc", "createdAt,asc", "updatedAt,desc", "updatedAt,asc", "total,desc", "total,asc");

    private final CurrentActorProvider actors;
    private final AdminOrderAuthorizationClient authorization;
    private final AdminOrderRepository repository;
    private final AdminOrderListingContextClient listingContext;
    private final PaymentIntentClient payments;
    private final InventoryReservationClient inventory;
    private final OrderCancellationProperties cancellationProperties;
    private final OrderCancellationProcessingProperties processingProperties;
    private final Clock clock;

    @Autowired
    public AdminOrderService(CurrentActorProvider actors, AdminOrderAuthorizationClient authorization,
            AdminOrderRepository repository, AdminOrderListingContextClient listingContext,
            PaymentIntentClient payments, InventoryReservationClient inventory,
            OrderCancellationProperties cancellationProperties,
            OrderCancellationProcessingProperties processingProperties) {
        this(actors, authorization, repository, listingContext, payments, inventory,
                cancellationProperties, processingProperties, Clock.systemUTC());
    }

    AdminOrderService(CurrentActorProvider actors, AdminOrderAuthorizationClient authorization,
            AdminOrderRepository repository, AdminOrderListingContextClient listingContext,
            PaymentIntentClient payments, InventoryReservationClient inventory,
            OrderCancellationProperties cancellationProperties,
            OrderCancellationProcessingProperties processingProperties, Clock clock) {
        this.actors = actors;
        this.authorization = authorization;
        this.repository = repository;
        this.listingContext = listingContext;
        this.payments = payments;
        this.inventory = inventory;
        this.cancellationProperties = cancellationProperties;
        this.processingProperties = processingProperties;
        this.clock = clock;
    }

    public Page search(String q, String buyerUserId, String businessId, String listingId,
            String status, String paymentStatus, String fulfillmentStatus, Instant createdFrom,
            Instant createdTo, int page, int size, String sort) {
        RequestContext context = require(AdminOrderPermission.READ);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String safeSort = sort == null || sort.isBlank() ? "createdAt,desc" : sort;
        if (!SORTS.contains(safeSort)) invalid("ORDER_ADMIN_SORT_INVALID", "Order sort is invalid.");
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            invalid("ORDER_ADMIN_DATE_RANGE_INVALID", "Created-from must be before created-to.");
        }
        SearchFilter filter = new SearchFilter(text(q, 100), id(buyerUserId, "Buyer user ID"),
                id(businessId, "Business ID"), id(listingId, "Listing ID"),
                enumValue(status, ORDER_STATUSES, "Order status"),
                enumValue(paymentStatus, PAYMENT_STATUSES, "Payment status"),
                enumValue(fulfillmentStatus, FULFILLMENT_STATUSES, "Fulfillment status"),
                createdFrom, createdTo, safePage, safeSize, safeSort);
        SearchResult result = repository.search(filter);
        Set<String> userIds = new LinkedHashSet<>();
        Set<String> businessIds = new LinkedHashSet<>();
        result.rows().forEach(row -> { userIds.add(row.buyerId()); businessIds.addAll(row.businessIds()); });
        AdminOrderAuthorizationClient.Labels labels = authorization.labels(
                context.actor().accessToken(), userIds, businessIds);
        Map<String, String> users = userLabels(labels);
        Map<String, String> businesses = businessLabels(labels);
        List<Summary> content = result.rows().stream().map(row -> new Summary(
                row.orderId(), row.buyerId(), users.get(row.buyerId()), "BUSINESS", row.businessIds(),
                row.businessIds().stream().map(value -> businesses.getOrDefault(value, value)).toList(),
                row.status(), row.paymentStatus(), row.fulfillmentStatus(), row.total(), row.currency(),
                row.itemCount(), row.createdAt(), row.updatedAt(), row.version())).toList();
        int pages = result.total() == 0 ? 0 : (int) ((result.total() + safeSize - 1) / safeSize);
        return new Page(content, safePage, safeSize, result.total(), pages, safeSort);
    }

    // Builds one administrative read model without replacing any owning service's source-of-truth data.
    public Detail detail(String rawOrderId) {
        RequestContext context = require(AdminOrderPermission.READ);
        String orderId = requiredId(rawOrderId, "Order ID");
        DetailRow order = repository.detail(orderId).orElseThrow(this::notFound);
        List<GroupRow> groups = repository.groups(orderId);
        List<ItemRow> items = repository.items(orderId);
        if (groups.isEmpty() || items.isEmpty()) {
            throw new AdminOrderException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_ADMIN_SNAPSHOT_INVALID",
                    "The stored order snapshot is incomplete.");
        }
        Set<String> businessIds = groups.stream().map(GroupRow::businessId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        AdminOrderAuthorizationClient.Labels labels = authorization.labels(context.actor().accessToken(),
                Set.of(order.buyerId()), businessIds);
        Map<String, String> users = userLabels(labels);
        Map<String, String> businesses = businessLabels(labels);
        Map<String, AdminOrderAuthorizationClient.BusinessLabel> businessDetails = new LinkedHashMap<>();
        labels.businesses().forEach(value -> businessDetails.put(value.id(), value));

        List<RelatedEnforcement> enforcement = new ArrayList<>();
        labels.enforcements().forEach(value -> enforcement.add(new RelatedEnforcement(
                value.targetType(), value.targetId(), value.actionType(), List.copyOf(value.scopes()),
                "USER".equals(value.targetType())
                        ? "/admin/users/" + value.targetId()
                        : "/admin/businesses/" + value.targetId())));
        List<Item> itemResponses = items.stream().map(item -> item(item, enforcement)).toList();
        List<BusinessSummary> groupResponses = groups.stream().map(group -> {
            var current = businessDetails.get(group.businessId());
            return new BusinessSummary(group.businessId(), businesses.get(group.businessId()), group.storeId(),
                    group.storeName(), current == null ? null : current.storeName(), group.fulfillmentStatus(),
                    group.cancellationStatus(), group.version(), "/admin/businesses/" + group.businessId());
        }).toList();
        AddressRow address = repository.address(orderId);
        boolean pii = context.access().has(AdminOrderPermission.USER_PII_READ);
        List<RefundRow> refunds = repository.refunds(orderId);
        BigDecimal refunded = refunds.stream().map(RefundRow::amount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CancellationRow cancellation = repository.cancellation(orderId);
        Evaluation evaluation = evaluate(order, groups, repository.policies(order.checkoutId()), clock.instant());
        return new Detail(order.orderId(), order.orderNumber(), order.status(), order.paymentStatus(),
                order.version(), order.createdAt(), order.updatedAt(),
                new BuyerSummary(order.buyerId(), users.get(order.buyerId()), "/admin/users/" + order.buyerId()),
                groupResponses, itemResponses,
                new Pricing(order.subtotal(), BigDecimal.ZERO, order.tax(), order.shipping(), order.discount(),
                        order.total(), order.currency()),
                address(address, pii), checkout(order), inventory(order), payment(order, refunded),
                refunds.stream().map(value -> new RefundSummary(value.source(), value.status(), value.refundId(),
                        value.providerReference(), value.amount(), value.currency(), value.completedAt())).toList(),
                cancellation(cancellation), enforcement, timeline(orderId),
                capabilities(context.access(), evaluation));
    }

    RequestContext require(AdminOrderPermission permission) {
        CurrentActor actor = actors.currentActor();
        AdminOrderAuthorizationClient.Access access = authorization.requireAdmin(actor.accessToken());
        if (!access.has(permission)) {
            throw new AdminOrderException(HttpStatus.FORBIDDEN, "ADMIN_ORDER_PERMISSION_REQUIRED",
                    "Administrative order permission is required.");
        }
        return new RequestContext(actor, access);
    }

    Evaluation evaluate(DetailRow order, List<GroupRow> groups, List<PolicyRow> policies, Instant now) {
        if ("CANCELLED".equals(order.status())) {
            return Evaluation.blocked("ORDER_ALREADY_CANCELLED", "The order is already cancelled.");
        }
        if (!"CONFIRMED".equals(order.status())) {
            return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED",
                    "Cancellation is unavailable in the current order state.");
        }
        if (!"SUCCEEDED".equals(order.paymentStatus())) {
            return Evaluation.blocked("ORDER_FINANCIAL_STATE_UNSUPPORTED",
                    "The payment state is not supported by administrative cancellation.");
        }
        if (!cancellationProperties.enabled() || !processingProperties.enabled()) {
            return Evaluation.blocked("ORDER_FINANCIAL_STATE_UNSUPPORTED",
                    "The refund and inventory compensation workflow is not enabled.");
        }
        if (groups.isEmpty()) {
            return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED", "The order has no fulfillment groups.");
        }
        Map<String, PolicyRow> byBusiness = new LinkedHashMap<>();
        policies.forEach(value -> byBusiness.put(value.businessId(), value));
        for (GroupRow group : groups) {
            if (!"PENDING_ACCEPTANCE".equals(group.fulfillmentStatus())) {
                return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED",
                        "Cancellation is unavailable after fulfillment begins.");
            }
            if (!"NONE".equals(group.cancellationStatus())) {
                return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED",
                        "A cancellation transition already exists for this order.");
            }
            PolicyRow policy = byBusiness.get(group.businessId());
            if (policy == null || !"BEFORE_FULFILLMENT".equals(policy.mode())) {
                return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED",
                        "The purchase-time policy does not allow cancellation.");
            }
            if (group.cancellationCutoffAt() == null || !now.isBefore(group.cancellationCutoffAt())) {
                return Evaluation.blocked("ORDER_ADMIN_ACTION_NOT_ALLOWED",
                        "The purchase-time cancellation window has closed.");
            }
        }
        return new Evaluation(true, null, null);
    }

    private Item item(ItemRow item, List<RelatedEnforcement> allEnforcement) {
        AdminOrderListingContextClient.Context current = listingContext.find(item.listingId());
        CurrentListing currentListing = null;
        if (current != null) {
            List<RelatedEnforcement> listingEnforcement = current.enforcement().stream()
                    .map(value -> new RelatedEnforcement("LISTING", item.listingId(), value.actionType(),
                            value.scopes(), "/admin/listings/moderation/" + item.listingId())).toList();
            allEnforcement.addAll(listingEnforcement);
            currentListing = new CurrentListing(true, current.title(), current.status(), current.price(),
                    current.currency(), current.version(), listingEnforcement);
        }
        return new Item(item.listingId(), item.lineNumber(), item.title(), item.sku(), item.condition(),
                item.thumbnailUrl(), item.catalogVersion(), item.businessId(), item.storeId(), item.unitPrice(),
                item.quantity(), item.lineSubtotal(), item.shipping(), item.tax(), item.discount(), item.lineTotal(),
                item.currency(), item.policyVersion(), currentListing,
                "/admin/listings/moderation/" + item.listingId());
    }

    private ShippingAddress address(AddressRow value, boolean pii) {
        if (value == null) return null;
        if (pii) return new ShippingAddress(false, value.label(), value.recipientName(), value.phone(),
                value.line1(), value.line2(), value.city(), value.region(), value.postalCode(), value.countryCode());
        return new ShippingAddress(true, null, null, null, null, null, value.city(), value.region(),
                maskPostal(value.postalCode()), value.countryCode());
    }

    private CheckoutSnapshot checkout(DetailRow order) {
        return new CheckoutSnapshot(order.checkoutId(), order.checkoutStatus(), order.checkoutVersion(),
                order.cartVersion(), order.cartSnapshotHash(), order.checkoutExpiresAt(),
                repository.policies(order.checkoutId()).stream().map(value -> new PolicySnapshot(
                        value.businessId(), value.version(), value.mode(), value.cancellationText(),
                        value.shippingText(), value.returnText())).toList());
    }

    private InventorySummary inventory(DetailRow order) {
        if (order.reservationId() == null) {
            return new InventorySummary(null, order.reservationStatus(), false, false,
                    order.reservationVersion(), null, List.of(), order.releaseStatus(),
                    "No reservation reference is stored for this checkout.");
        }
        try {
            InventoryReservationClient.Reservation value = inventory.get(order.reservationId());
            return new InventorySummary(value.id(), value.status(), true, value.usable(), value.version(),
                    value.expiresAt(), value.items().stream()
                            .map(item -> new InventoryLine(item.listingId(), item.quantity())).toList(),
                    order.releaseStatus(), null);
        } catch (RuntimeException exception) {
            return new InventorySummary(order.reservationId(), order.reservationStatus(), false, false,
                    order.reservationVersion(), null, List.of(), order.releaseStatus(),
                    "Live inventory state is temporarily unavailable; showing the last Order projection.");
        }
    }

    private PaymentSummary payment(DetailRow order, BigDecimal refunded) {
        try {
            PaymentIntentClient.PaymentIntent value = payments.get(order.paymentIntentId(), order.buyerId());
            BigDecimal captured = "SUCCEEDED".equals(value.status()) ? value.amount() : BigDecimal.ZERO;
            return new PaymentSummary(value.id(), value.status(), value.provider(), value.providerReference(),
                    value.amount(), captured, refunded, value.currency(), true, "CURRENT", null);
        } catch (CheckoutException | UnsupportedOperationException exception) {
            return new PaymentSummary(order.paymentIntentId(), order.paymentStatus(), null, null,
                    order.total(), "SUCCEEDED".equals(order.paymentStatus()) ? order.total() : BigDecimal.ZERO,
                    refunded, order.currency(), false, "ORDER_PROJECTION",
                    "Live payment state is temporarily unavailable; showing the confirmed Order projection.");
        }
    }

    private CancellationSummary cancellation(CancellationRow value) {
        return value == null ? null : new CancellationSummary(value.requestId(), value.status(), value.actorType(),
                value.actorId(), value.reasonCode(), value.reason(), value.requestedAt(), value.decidedAt(),
                value.completedAt(), value.inventoryStatus(), value.refundStatus());
    }

    private List<TimelineEntry> timeline(String orderId) {
        return repository.timeline(orderId).stream().map(value -> new TimelineEntry(value.eventId(),
                value.occurredAt(), value.eventType(), value.actorType(), value.actorId(),
                value.actorDisplayName(), "PLATFORM_ADMIN".equals(value.actorType()) ? "HUMAN_ADMIN" : "SYSTEM",
                value.previousState(), value.newState(), value.reason(), value.correlationId(), value.requestId(),
                Map.of())).toList();
    }

    private Capabilities capabilities(AdminOrderAuthorizationClient.Access access, Evaluation evaluation) {
        boolean canCancel = access.has(AdminOrderPermission.CANCEL);
        boolean allowed = canCancel && evaluation.allowed();
        String reason = !canCancel ? "Administrative cancellation permission is required."
                : evaluation.blockerMessage();
        return new Capabilities(true, canCancel, access.has(AdminOrderPermission.MANAGE),
                access.has(AdminOrderPermission.USER_PII_READ), allowed, !allowed, reason);
    }

    private Map<String, String> userLabels(AdminOrderAuthorizationClient.Labels labels) {
        Map<String, String> result = new LinkedHashMap<>();
        labels.users().forEach(value -> result.put(value.id(), value.displayName())); return result;
    }

    private Map<String, String> businessLabels(AdminOrderAuthorizationClient.Labels labels) {
        Map<String, String> result = new LinkedHashMap<>();
        labels.businesses().forEach(value -> result.put(value.id(),
                value.storeName() == null ? value.legalName() : value.storeName())); return result;
    }

    private String requiredId(String value, String label) {
        String normalized = id(value, label);
        if (normalized == null) invalid("ORDER_ID_INVALID", label + " is required.");
        return normalized;
    }

    private String id(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (!ULID.matcher(normalized).matches()) invalid("ORDER_ADMIN_FILTER_INVALID", label + " is invalid.");
        return normalized;
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) invalid("ORDER_ADMIN_FILTER_INVALID", "Order query is too long.");
        return normalized;
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) invalid("ORDER_ADMIN_FILTER_INVALID", label + " is invalid.");
        return normalized;
    }

    private String maskPostal(String value) {
        if (value == null || value.length() < 2) return null;
        return value.substring(0, Math.min(2, value.length())) + "***";
    }

    private AdminOrderException notFound() {
        return new AdminOrderException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found.");
    }

    private void invalid(String code, String message) {
        throw new AdminOrderException(HttpStatus.BAD_REQUEST, code, message);
    }

    record RequestContext(CurrentActor actor, AdminOrderAuthorizationClient.Access access) { }
    record Evaluation(boolean allowed, String blockerCode, String blockerMessage) {
        static Evaluation blocked(String code, String message) { return new Evaluation(false, code, message); }
    }
}
