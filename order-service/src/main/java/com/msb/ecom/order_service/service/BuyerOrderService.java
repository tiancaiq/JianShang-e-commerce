package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BuyerOrderProperties;
import com.msb.ecom.order_service.dto.BuyerOrderDetailResponse;
import com.msb.ecom.order_service.dto.BuyerOrderPageResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.model.BuyerOrderView;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.repository.BuyerOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class BuyerOrderService {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final BuyerOrderProperties properties;
    private final CurrentActorProvider actorProvider;
    private final BuyerIdentityClient buyerIdentityClient;
    private final BuyerOrderRepository repository;
    private final BuyerOrderMetrics metrics;

    public BuyerOrderService(
            BuyerOrderProperties properties,
            CurrentActorProvider actorProvider,
            BuyerIdentityClient buyerIdentityClient,
            BuyerOrderRepository repository,
            BuyerOrderMetrics metrics) {
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.buyerIdentityClient = buyerIdentityClient;
        this.repository = repository;
        this.metrics = metrics;
    }

    // Returns a stable buyer-owned timeline using only approved order projections.
    public BuyerOrderPageResponse list(String cursorValue, String limitValue) {
        requireEnabled("list");
        BuyerOrderCursorCodec.Cursor cursor = BuyerOrderCursorCodec.decode(cursorValue);
        int limit = BuyerOrderCursorCodec.limit(limitValue);
        String buyerId = resolveBuyer();
        List<BuyerOrderView> loaded = repository.findPage(
                buyerId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.orderId(),
                limit + 1);
        if (loaded.stream().anyMatch(order -> order.groups().isEmpty())) {
            throw invalidSnapshot("list");
        }
        boolean hasMore = loaded.size() > limit;
        List<BuyerOrderView> visible = hasMore ? loaded.subList(0, limit) : loaded;
        String nextCursor = hasMore
                ? BuyerOrderCursorCodec.encode(visible.get(visible.size() - 1))
                : null;
        metrics.read("list", "success");
        return new BuyerOrderPageResponse(
                visible.stream().map(this::summary).toList(),
                new BuyerOrderPageResponse.PageMetadata(nextCursor, hasMore));
    }

    // Resolves ownership before returning immutable order detail snapshots.
    public BuyerOrderDetailResponse detail(String orderId) {
        requireEnabled("detail");
        if (orderId == null || !ULID.matcher(orderId).matches()) {
            metrics.read("detail", "invalid");
            throw new BuyerOrderException(
                    HttpStatus.BAD_REQUEST,
                    "ORDER_ID_INVALID",
                    "Order ID is invalid.");
        }
        String buyerId = resolveBuyer();
        BuyerOrderView order = repository.findOwnedDetail(buyerId, orderId)
                .orElseThrow(() -> {
                    metrics.read("detail", "not_found");
                    return notFound();
                });
        if (order.groups().isEmpty()
                || order.groups().stream().anyMatch(group -> group.items().isEmpty())
                || order.shippingAddress() == null) {
            throw invalidSnapshot("detail");
        }
        metrics.read("detail", "success");
        return detail(order);
    }

    private BuyerOrderPageResponse.OrderSummary summary(BuyerOrderView order) {
        return new BuyerOrderPageResponse.OrderSummary(
                order.orderId(),
                order.status(),
                order.paymentStatus(),
                order.totalAmount(),
                order.currency(),
                order.createdAt(),
                order.updatedAt(),
                order.groups().stream()
                        .map(group -> new BuyerOrderPageResponse.GroupSummary(
                                group.businessOrderId(),
                                group.businessId(),
                                group.storeName(),
                                group.status(),
                                group.totalAmount(),
                                order.currency()))
                        .toList());
    }

    private BuyerOrderDetailResponse detail(BuyerOrderView order) {
        BuyerOrderView.Address address = order.shippingAddress();
        var cancellation = repository.cancellation(order.orderId());
        if (cancellation == null) {
            cancellation = new BuyerOrderRepository.CancellationView(
                    false, "POLICY_NOT_ALLOWED", null, null,
                    null, null, null, null, null, null, null, null, null);
        }
        return new BuyerOrderDetailResponse(
                order.orderId(),
                order.status(),
                order.paymentStatus(),
                order.totalAmount(),
                order.currency(),
                order.version(),
                order.createdAt(),
                order.updatedAt(),
                order.groups().stream().map(group -> new BuyerOrderDetailResponse.Group(
                        group.businessOrderId(),
                        group.businessId(),
                        group.storeId(),
                        group.storeName(),
                        group.status(),
                        group.totalAmount(),
                        order.currency(),
                        group.items().stream().map(item -> new BuyerOrderDetailResponse.Item(
                                item.listingId(),
                                item.title(),
                                item.businessId(),
                                item.storeId(),
                                item.unitPrice(),
                                item.currency(),
                                item.quantity(),
                                item.lineTotal(),
                                item.policyVersion()))
                                .toList(),
                        group.version(),
                        group.timeline().stream()
                                .map(entry -> new BuyerOrderDetailResponse.TimelineEntry(
                                        entry.status(), entry.occurredAt()))
                                .toList(),
                        group.shipment() == null ? null : new BuyerOrderDetailResponse.Shipment(
                                group.shipment().shipmentId(), group.shipment().source(),
                                group.shipment().carrierDisplayName(),
                                group.shipment().serviceDisplayName(),
                                group.shipment().trackingNumber(), group.shipment().status(),
                                group.shipment().version(), group.shipment().shippedAt(),
                                group.shipment().deliveredAt(), group.shipment().createdAt(),
                                group.shipment().updatedAt())))
                        .toList(),
                new BuyerOrderDetailResponse.ShippingAddress(
                        address.label(),
                        address.recipientName(),
                        address.phone(),
                        address.line1(),
                        address.line2(),
                        address.city(),
                        address.region(),
                        address.postalCode(),
                        address.countryCode()),
                new BuyerOrderDetailResponse.Cancellation(
                        cancellation.eligible(), cancellation.ineligibilityCode(),
                        cancellation.requestId(), cancellation.requestStatus(),
                        cancellation.requestId() == null ? null : "BUYER_CANCELLATION_REQUESTED",
                        cancellation.requestedAt(), cancellation.decidedAt(),
                        cancellation.completedAt(),
                        cancellation.inventoryStatus(),
                        cancellation.refundStatus() == null ? null
                                : new BuyerOrderDetailResponse.Refund(
                                        cancellation.refundStatus(), cancellation.refundId(),
                                        cancellation.refundAmount(),
                                        cancellation.refundCurrency(), "Local demo refund",
                                        "No real money is moved.")));
    }

    private String resolveBuyer() {
        try {
            String buyerId =
                    buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
            if (buyerId == null || buyerId.isBlank()) {
                throw new IllegalStateException("Buyer identity was empty.");
            }
            return buyerId;
        } catch (CheckoutException exception) {
            metrics.read("identity", "unavailable");
            throw new BuyerOrderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDERS_DEPENDENCY_UNAVAILABLE",
                    "Buyer order lookup is temporarily unavailable.");
        } catch (IllegalStateException exception) {
            metrics.read("identity", "unavailable");
            throw new BuyerOrderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDERS_DEPENDENCY_UNAVAILABLE",
                    "Buyer order lookup is temporarily unavailable.");
        }
    }

    private void requireEnabled(String operation) {
        if (!properties.enabled()) {
            metrics.read(operation, "disabled");
            throw new BuyerOrderException(
                    HttpStatus.NOT_FOUND,
                    "ORDERS_NOT_AVAILABLE",
                    "Buyer orders are not available.");
        }
    }

    private BuyerOrderException notFound() {
        return new BuyerOrderException(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "Order was not found.");
    }

    private BuyerOrderException invalidSnapshot(String operation) {
        metrics.read(operation, "invalid_snapshot");
        return new BuyerOrderException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ORDER_SNAPSHOT_INVALID",
                "Order detail is temporarily unavailable.");
    }
}
