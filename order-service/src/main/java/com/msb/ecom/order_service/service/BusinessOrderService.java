package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderProperties;
import com.msb.ecom.order_service.dto.BusinessOrderDetailResponse;
import com.msb.ecom.order_service.dto.BusinessOrderPageResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.model.BusinessOrderView;
import com.msb.ecom.order_service.repository.BusinessOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class BusinessOrderService {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Set<String> STATUSES = Set.of(
            "PENDING_ACCEPTANCE",
            "ACCEPTED",
            "PARTIALLY_SHIPPED",
            "SHIPPED",
            "DELIVERED",
            "CANCELLATION_PENDING",
            "CANCELLED");

    private final BusinessOrderProperties properties;
    private final CurrentActorProvider actorProvider;
    private final BusinessOrderAuthorizationClient authorizationClient;
    private final BusinessOrderRepository repository;
    private final BusinessOrderMetrics metrics;

    public BusinessOrderService(
            BusinessOrderProperties properties,
            CurrentActorProvider actorProvider,
            BusinessOrderAuthorizationClient authorizationClient,
            BusinessOrderRepository repository,
            BusinessOrderMetrics metrics) {
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.authorizationClient = authorizationClient;
        this.repository = repository;
        this.metrics = metrics;
    }

    // Returns only fulfillment groups owned by the authorized path business.
    public BusinessOrderPageResponse list(
            String businessId,
            String statusValue,
            String cursorValue,
            String limitValue) {
        requireEnabled("list");
        requireId("business", businessId);
        String status = status(statusValue);
        BusinessOrderCursorCodec.Cursor cursor = BusinessOrderCursorCodec.decode(cursorValue);
        int limit = BusinessOrderCursorCodec.limit(limitValue);
        BusinessOrderAuthorizationClient.Access access = authorize(businessId);
        List<BusinessOrderView> loaded = repository.findPage(
                businessId,
                status,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.businessOrderId(),
                limit + 1,
                access.financeView());
        boolean hasMore = loaded.size() > limit;
        List<BusinessOrderView> visible = hasMore ? loaded.subList(0, limit) : loaded;
        String nextCursor = hasMore
                ? BusinessOrderCursorCodec.encode(visible.get(visible.size() - 1))
                : null;
        metrics.read("list", "success");
        return new BusinessOrderPageResponse(
                visible.stream().map(this::summary).toList(),
                new BusinessOrderPageResponse.PageMetadata(nextCursor, hasMore));
    }

    // Resolves membership before loading one immutable business-owned group snapshot.
    public BusinessOrderDetailResponse detail(String businessId, String businessOrderId) {
        requireEnabled("detail");
        requireId("business", businessId);
        requireId("business order", businessOrderId);
        BusinessOrderAuthorizationClient.Access access = authorize(businessId);
        BusinessOrderView order = repository.findOwnedDetail(
                        businessId,
                        businessOrderId,
                        access.financeView())
                .orElseThrow(() -> {
                    metrics.read("detail", "not_found");
                    return notFound();
                });
        if (order.items().isEmpty() || order.shippingAddress() == null) {
            metrics.read("detail", "invalid_snapshot");
            throw new BusinessOrderException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "BUSINESS_ORDER_SNAPSHOT_INVALID",
                    "Business order detail is temporarily unavailable.");
        }
        metrics.read("detail", "success");
        return detail(order);
    }

    private BusinessOrderAuthorizationClient.Access authorize(String businessId) {
        CurrentActor actor = actorProvider.currentActor();
        if (actor.accessToken() == null || actor.accessToken().isBlank()) {
            throw new BusinessOrderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "BUSINESS_ORDERS_DEPENDENCY_UNAVAILABLE",
                    "Business orders are temporarily unavailable.");
        }
        try {
            return authorizationClient.authorize(actor.accessToken(), businessId);
        } catch (BusinessOrderException exception) {
            metrics.read("authorization", exception.status() == HttpStatus.NOT_FOUND
                    ? "not_found"
                    : "unavailable");
            throw exception;
        }
    }

    private BusinessOrderPageResponse.OrderSummary summary(BusinessOrderView order) {
        return new BusinessOrderPageResponse.OrderSummary(
                order.businessOrderId(),
                order.sellerOrderNumber(),
                order.businessId(),
                order.storeId(),
                order.status(),
                order.cancellationStatus(),
                order.buyerOrderId(),
                order.buyerOrderNumber(),
                order.itemCount(),
                order.totalQuantity(),
                order.subtotal(),
                order.totalAmount(),
                order.currency(),
                order.platformFeeProjection(),
                order.confirmedAt(),
                order.createdAt(),
                order.updatedAt());
    }

    private BusinessOrderDetailResponse detail(BusinessOrderView order) {
        BusinessOrderView.Address address = order.shippingAddress();
        return new BusinessOrderDetailResponse(
                order.businessOrderId(),
                order.sellerOrderNumber(),
                order.businessId(),
                order.storeId(),
                order.status(),
                order.cancellationStatus(),
                order.buyerOrderId(),
                order.buyerOrderNumber(),
                order.paymentStatus(),
                order.itemCount(),
                order.totalQuantity(),
                order.subtotal(),
                order.totalAmount(),
                order.currency(),
                order.platformFeeProjection(),
                order.confirmedAt(),
                order.createdAt(),
                order.updatedAt(),
                order.items().stream().map(item -> new BusinessOrderDetailResponse.Item(
                        item.listingId(),
                        item.title(),
                        item.sku(),
                        item.itemCondition(),
                        item.thumbnailUrl(),
                        item.unitPrice(),
                        item.currency(),
                        item.quantity(),
                        item.lineTotal(),
                        item.policyVersion())).toList(),
                new BusinessOrderDetailResponse.ShippingAddress(
                        address.recipientName(),
                        address.phone(),
                        address.line1(),
                        address.line2(),
                        address.city(),
                        address.region(),
                        address.postalCode(),
                        address.countryCode()));
    }

    private void requireEnabled(String operation) {
        if (!properties.enabled()) {
            metrics.read(operation, "disabled");
            throw new BusinessOrderException(
                    HttpStatus.NOT_FOUND,
                    "BUSINESS_ORDERS_NOT_AVAILABLE",
                    "Business orders are not available.");
        }
    }

    private void requireId(String label, String value) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new BusinessOrderException(
                    HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_ID_INVALID",
                    Character.toUpperCase(label.charAt(0)) + label.substring(1)
                            + " ID is invalid.");
        }
    }

    private String status(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!STATUSES.contains(value)) {
            throw new BusinessOrderException(
                    HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_STATUS_INVALID",
                    "Business order status is invalid.");
        }
        return value;
    }

    private BusinessOrderException notFound() {
        return new BusinessOrderException(
                HttpStatus.NOT_FOUND,
                "BUSINESS_ORDER_NOT_FOUND",
                "Business order was not found.");
    }
}
