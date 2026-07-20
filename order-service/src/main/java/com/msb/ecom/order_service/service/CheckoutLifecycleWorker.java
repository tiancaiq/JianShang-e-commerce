package com.msb.ecom.order_service.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CheckoutLifecycleWorker {

    private final CheckoutService checkoutService;

    public CheckoutLifecycleWorker(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${checkout.expiry-interval-ms:5000}")
    public void expireAndRelease() {
        checkoutService.expireDue();
        checkoutService.reconcilePendingReleases();
    }

    @Scheduled(fixedDelayString = "${checkout.recovery-interval-ms:5000}")
    public void recoverReservations() {
        checkoutService.recoverReserving();
    }
}
