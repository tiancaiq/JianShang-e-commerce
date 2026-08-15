package com.msb.ecom.order_service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OrderNotificationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OrderNotificationOutboxWorker.class);
    private static final Map<String, String> BUYER_TYPES = Map.of(
            "order.confirmed", "BUYER_ORDER_CONFIRMED",
            "business_order.accepted", "BUYER_ORDER_ACCEPTED",
            "business_order.processing_started", "BUYER_ORDER_PROCESSING",
            "business_order.shipped", "BUYER_ORDER_SHIPPED",
            "business_order.delivered_demo", "BUYER_ORDER_DELIVERED");

    private final boolean enabled;
    private final boolean workerEnabled;
    private final int batchSize;
    private final Duration retryDelay;
    private final OrderNotificationOutboxRepository repository;
    private final NotificationEventTransport transport;
    private final Clock clock = Clock.systemUTC();

    public OrderNotificationOutboxWorker(
            @Value("${order.notification-outbox.enabled:false}") boolean enabled,
            @Value("${order.notification-outbox.worker-enabled:false}") boolean workerEnabled,
            @Value("${order.notification-outbox.batch-size:25}") int batchSize,
            @Value("${order.notification-outbox.retry-delay:PT2S}") Duration retryDelay,
            OrderNotificationOutboxRepository repository,
            NotificationEventTransport transport) {
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
        this.repository = repository;
        this.transport = transport;
    }

    @Scheduled(fixedDelayString = "${order.notification-outbox.interval-ms:500}")
    public void dispatch() {
        if (!enabled || !workerEnabled) return;
        for (OrderNotificationOutboxRepository.OutboxRecord record
                : repository.due(clock.instant(), batchSize)) {
            try {
                transport.send(project(record));
                repository.published(record.id(), clock.instant());
            } catch (RuntimeException exception) {
                repository.failed(record.id(), clock.instant().plus(retryDelay),
                        "NOTIFICATION_DELIVERY_UNAVAILABLE");
                log.warn("Notification outbox delivery deferred eventType={} eventRef={}",
                        record.eventType(), record.id());
            }
        }
    }

    // Enriches only recipient and display projections from the Order-owned schema.
    CommerceNotificationEvent project(OrderNotificationOutboxRepository.OutboxRecord record) {
        OrderNotificationOutboxRepository.OrderAudience audience = repository.audience(record);
        List<CommerceNotificationEvent.Target> targets = new ArrayList<>();
        String buyerType = BUYER_TYPES.get(record.eventType());
        String relevantId = "RETURN".equals(record.aggregateType())
                ? repository.returnBusinessOrderId(record.aggregateId()) : record.aggregateId();
        OrderNotificationOutboxRepository.BusinessAudience relevant =
                ("BUSINESS_ORDER".equals(record.aggregateType()) || "RETURN".equals(record.aggregateType()))
                    ? audience.businesses().stream()
                        .filter(item -> item.businessOrderId().equals(relevantId))
                        .findFirst().orElseThrow() : null;
        if (buyerType != null) {
            targets.add(new CommerceNotificationEvent.Target(
                    "USER", audience.buyerId(), buyerType, audience.orderId(),
                    relevant == null ? null : relevant.businessId(),
                    relevant == null ? null : relevant.businessOrderId(),
                    relevant == null ? null : relevant.storeDisplayName()));
        }
        if ("order.confirmed".equals(record.eventType())) {
            for (var business : audience.businesses()) {
                targets.add(seller(audience.orderId(), business, "SELLER_NEW_ORDER"));
            }
        }
        if ("order.cancellation_completed".equals(record.eventType())) {
            targets.add(new CommerceNotificationEvent.Target(
                    "USER", audience.buyerId(), "BUYER_ORDER_CANCELLED",
                    audience.orderId(), null, null, null));
            targets.add(new CommerceNotificationEvent.Target(
                    "USER", audience.buyerId(), "BUYER_REFUND_COMPLETED",
                    audience.orderId(), null, null, null));
            for (var business : audience.businesses()) {
                targets.add(seller(audience.orderId(), business, "SELLER_ORDER_CANCELLED"));
            }
        }
        if ("return.requested".equals(record.eventType())) {
            targets.add(seller(audience.orderId(), relevant, "SELLER_RETURN_REQUESTED"));
        }
        if ("return.authorized".equals(record.eventType())) {
            targets.add(buyer(audience, relevant, "BUYER_RETURN_AUTHORIZED"));
        }
        if ("return.received".equals(record.eventType())) {
            targets.add(buyer(audience, relevant, "BUYER_RETURN_RECEIVED"));
        }
        if ("return.refund_completed".equals(record.eventType())) {
            targets.add(buyer(audience, relevant, "BUYER_RETURN_REFUND_COMPLETED"));
        }
        return new CommerceNotificationEvent(
                record.id(), record.eventType(), record.eventVersion(), record.occurredAt(),
                "order-service", record.aggregateType(), record.aggregateId(),
                record.correlationId() == null ? record.id() : record.correlationId(),
                record.causationId(), List.copyOf(targets));
    }

    private CommerceNotificationEvent.Target buyer(OrderNotificationOutboxRepository.OrderAudience audience,
            OrderNotificationOutboxRepository.BusinessAudience business, String type) {
        return new CommerceNotificationEvent.Target("USER", audience.buyerId(), type,
                audience.orderId(), business.businessId(), business.businessOrderId(),
                business.storeDisplayName());
    }

    private CommerceNotificationEvent.Target seller(
            String orderId, OrderNotificationOutboxRepository.BusinessAudience business, String type) {
        return new CommerceNotificationEvent.Target(
                "BUSINESS", business.businessId(), type, orderId, business.businessId(),
                business.businessOrderId(), business.storeDisplayName());
    }
}
