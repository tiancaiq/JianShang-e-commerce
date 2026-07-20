package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.inventory_service.dto.InventoryReservationItemResponse;
import com.msb.ecom.inventory_service.dto.InventoryReservationRequest;
import com.msb.ecom.inventory_service.dto.InventoryReservationResponse;
import com.msb.ecom.inventory_service.model.ReservationPurpose;
import com.msb.ecom.inventory_service.model.ReservationStatus;
import com.msb.ecom.inventory_service.repository.IdempotencyRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import com.msb.ecom.inventory_service.repository.InventoryReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryReservationReplayTests {

    @Test
    void completedReserveReplaysAfterItsDeadline() throws Exception {
        Instant now = Instant.parse("2026-07-19T12:30:00Z");
        Instant expiredAt = now.minusSeconds(60);
        String checkoutId = "01C00000000000000000000001";
        String listingId = "01L00000000000000000000001";
        String reservationId = "01R00000000000000000000001";
        String key = "checkout-reserve:" + checkoutId;
        InventoryReservationRequest request = new InventoryReservationRequest(
                checkoutId,
                ReservationPurpose.CHECKOUT,
                expiredAt,
                List.of(new InventoryReservationRequest.Item(listingId, 2)));
        InventoryReservationResponse stored = new InventoryReservationResponse(
                reservationId,
                checkoutId,
                ReservationPurpose.CHECKOUT,
                ReservationStatus.ACTIVE,
                true,
                expiredAt,
                null,
                null,
                null,
                0,
                expiredAt.minusSeconds(900),
                expiredAt.minusSeconds(900),
                List.of(new InventoryReservationItemResponse(
                        "01I00000000000000000000001",
                        "01B00000000000000000000001",
                        listingId,
                        2,
                        5,
                        2,
                        3,
                        1)));

        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        when(inventoryRepository.findIdempotency(
                "ORDER_SERVICE:RESERVE:" + checkoutId + ":CHECKOUT",
                key)).thenReturn(Optional.of(new IdempotencyRecord(
                reserveHash(checkoutId, expiredAt, listingId, 2),
                201,
                new ObjectMapper().findAndRegisterModules().writeValueAsString(stored))));

        InventoryReservationService service = new InventoryReservationService(
                inventoryRepository,
                mock(InventoryReservationRepository.class),
                new InternalCommerceAuthenticator("token"),
                new InventoryReservationMetrics(new SimpleMeterRegistry()),
                new UlidGenerator(),
                new ObjectMapper().findAndRegisterModules(),
                mock(PlatformTransactionManager.class),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofHours(24),
                50);

        InventoryReservationResponse replay = service.reserve("token", key, request, "correlation");

        assertThat(replay.id()).isEqualTo(reservationId);
        assertThat(replay.expiresAt()).isEqualTo(expiredAt);
    }

    private String reserveHash(
            String checkoutId,
            Instant expiresAt,
            String listingId,
            int quantity) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of(
                "RESERVE",
                checkoutId,
                "CHECKOUT",
                expiresAt.toString(),
                listingId,
                Integer.toString(quantity))) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
