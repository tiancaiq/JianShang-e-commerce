package com.msb.ecom.inventory_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservationExpiryWorker {

    private final InventoryReservationService reservationService;
    private final boolean enabled;

    public InventoryReservationExpiryWorker(
            InventoryReservationService reservationService,
            @Value("${inventory.reservations.expiry-enabled:true}") boolean enabled) {
        this.reservationService = reservationService;
        this.enabled = enabled;
    }

    // Runs bounded expiry work; state locking makes repeated or overlapping scans harmless.
    @Scheduled(fixedDelayString = "${inventory.reservations.expiry-interval-ms:5000}")
    public void expireDueReservations() {
        if (enabled) {
            reservationService.expireDueReservations();
        }
    }
}
