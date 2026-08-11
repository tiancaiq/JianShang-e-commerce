package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.inventory_service.config.InventoryCancellationRestockProperties;
import com.msb.ecom.inventory_service.dto.InventoryCancellationRestockRequest;
import com.msb.ecom.inventory_service.dto.InventoryCancellationRestockResponse;
import com.msb.ecom.inventory_service.model.InventoryException;
import com.msb.ecom.inventory_service.model.ReservationStatus;
import com.msb.ecom.inventory_service.repository.InventoryCancellationRestockRepository;
import com.msb.ecom.inventory_service.repository.InventoryCancellationRestockRepository.RestockRecord;
import com.msb.ecom.inventory_service.repository.InventoryItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import com.msb.ecom.inventory_service.repository.InventoryReservationItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class InventoryCancellationRestockService {

    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private final InventoryCancellationRestockProperties properties;
    private final InternalCommerceAuthenticator authenticator;
    private final InventoryCancellationRestockRepository restocks;
    private final InventoryReservationRepository reservations;
    private final InventoryRepository inventory;
    private final UlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InventoryCancellationRestockService(
            InventoryCancellationRestockProperties properties,
            InternalCommerceAuthenticator authenticator,
            InventoryCancellationRestockRepository restocks,
            InventoryReservationRepository reservations,
            InventoryRepository inventory,
            UlidGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.restocks = restocks;
        this.reservations = reservations;
        this.inventory = inventory;
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
    }

    // Restores exactly the quantities consumed by one committed checkout reservation.
    public InventoryCancellationRestockResponse restock(
            String internalToken,
            String reservationId,
            String idempotencyKey,
            InventoryCancellationRestockRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(internalToken);
        if (!properties.enabled()) {
            throw error(HttpStatus.NOT_FOUND, "INVENTORY_CANCELLATION_RESTOCK_NOT_AVAILABLE",
                    "Cancellation inventory recovery is not available.");
        }
        requireUlid(reservationId, "reservationId");
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVENTORY_CANCELLATION_RESTOCK_INVALID",
                    "A cancellation recovery request is required.");
        }
        requireUlid(request.orderId(), "orderId");
        requireUlid(request.cancellationRequestId(), "cancellationRequestId");
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVENTORY_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
        RestockRecord keyed = restocks.findByKey(idempotencyKey).orElse(null);
        if (keyed != null) {
            return replay(keyed, reservationId, request);
        }
        return transactions.execute(status -> execute(
                reservationId, idempotencyKey, request,
                correlationId == null || correlationId.isBlank() ? ids.next() : correlationId,
                clock.instant()));
    }

    private InventoryCancellationRestockResponse execute(
            String reservationId,
            String idempotencyKey,
            InventoryCancellationRestockRequest request,
            String correlationId,
            Instant now) {
        RestockRecord existing = restocks.findByRequest(request.cancellationRequestId()).orElse(null);
        if (existing != null) {
            return replay(existing, reservationId, request);
        }
        var reservation = reservations.lock(reservationId).orElseThrow(() -> error(
                HttpStatus.NOT_FOUND, "INVENTORY_RESERVATION_NOT_FOUND", "Reservation was not found."));
        if (reservation.status() != ReservationStatus.COMMITTED) {
            throw error(HttpStatus.CONFLICT, "INVENTORY_CANCELLATION_RESTOCK_STATE_CONFLICT",
                    "Only a committed checkout reservation can be restored.");
        }
        List<InventoryReservationItemRecord> lines = restocks.reservationItems(reservationId);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Committed reservation has no inventory lines.");
        }
        Map<String, InventoryItemRecord> locked = inventory.lockItemsByListingIds(
                lines.stream().map(InventoryReservationItemRecord::listingId).toList());
        int restored = 0;
        for (InventoryReservationItemRecord line : lines) {
            InventoryItemRecord item = locked.get(line.listingId());
            if (item == null || !item.id().equals(line.inventoryItemId())) {
                throw new IllegalStateException("Reservation inventory evidence is inconsistent.");
            }
            int after = Math.addExact(item.onHand(), line.quantity());
            if (inventory.updateBalances(item.id(), item.version(), after, item.reserved(), now) != 1) {
                throw new IllegalStateException("Cancellation restock lost its inventory lock.");
            }
            inventory.insertMovement(
                    ids.next(), item, "ADJUST", "ORDER_CANCELLATION_RESTOCK",
                    line.quantity(), item.onHand(), after,
                    "Restored from cancelled order " + request.orderId(),
                    request.cancellationRequestId(), ids.next(), correlationId, now);
            restored = Math.addExact(restored, line.quantity());
        }
        String restockId = ids.next();
        restocks.insert(restockId, request.cancellationRequestId(), request.orderId(),
                reservationId, idempotencyKey, restored, correlationId, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("restockId", restockId);
        payload.put("orderId", request.orderId());
        payload.put("cancellationRequestId", request.cancellationRequestId());
        payload.put("reservationId", reservationId);
        payload.put("restoredQuantity", restored);
        inventory.insertOutbox(ids.next(), "INVENTORY_RESERVATION", reservationId,
                "inventory.order-cancellation-restocked.v1", json(payload),
                correlationId, request.cancellationRequestId(), now);
        return new InventoryCancellationRestockResponse(restockId, request.orderId(),
                request.cancellationRequestId(), reservationId, "COMPLETED", restored, now);
    }

    private InventoryCancellationRestockResponse replay(
            RestockRecord record,
            String reservationId,
            InventoryCancellationRestockRequest request) {
        if (!record.reservationId().equals(reservationId)
                || !record.orderId().equals(request.orderId())
                || !record.cancellationRequestId().equals(request.cancellationRequestId())) {
            throw error(HttpStatus.CONFLICT, "INVENTORY_CANCELLATION_RESTOCK_IDEMPOTENCY_CONFLICT",
                    "The cancellation recovery command conflicts with an existing command.");
        }
        return new InventoryCancellationRestockResponse(record.id(), record.orderId(),
                record.cancellationRequestId(), record.reservationId(), record.status(),
                record.restoredQuantity(), record.completedAt());
    }

    private void requireUlid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVENTORY_CANCELLATION_RESTOCK_INVALID",
                    field + " is invalid.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Inventory cancellation event serialization failed.", exception);
        }
    }

    private InventoryException error(HttpStatus status, String code, String message) {
        return new InventoryException(status, code, message);
    }
}
