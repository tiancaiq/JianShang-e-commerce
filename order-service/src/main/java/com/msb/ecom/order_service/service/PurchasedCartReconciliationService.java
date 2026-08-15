package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.PurchasedCartReconciliation;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.repository.CartRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class PurchasedCartReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PurchasedCartReconciliationService.class);
    private static final int BATCH_SIZE = 20;

    private final CheckoutRepository checkouts;
    private final CartRepository carts;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public PurchasedCartReconciliationService(
            CheckoutRepository checkouts,
            CartRepository carts,
            CheckoutProperties properties) {
        this(checkouts, carts, Clock.systemUTC(), properties.enabled());
    }

    PurchasedCartReconciliationService(CheckoutRepository checkouts, CartRepository carts, Clock clock) {
        this(checkouts, carts, clock, true);
    }

    private PurchasedCartReconciliationService(
            CheckoutRepository checkouts,
            CartRepository carts,
            Clock clock,
            boolean enabled) {
        this.checkouts = checkouts;
        this.carts = carts;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${checkout.cart-reconciliation-interval-ms:2000}")
    public void reconcilePending() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        for (PurchasedCartReconciliation reconciliation
                : checkouts.findPendingCartReconciliations(now, BATCH_SIZE)) {
            reconcile(reconciliation);
        }
    }

    // Cart cleanup is deliberately outside order confirmation and retries independently.
    void reconcile(PurchasedCartReconciliation reconciliation) {
        Instant now = clock.instant();
        try {
            carts.reconcilePurchased(reconciliation);
            checkouts.markCartReconciled(reconciliation.checkoutId(), now);
            log.info(
                    "Purchased cart lines reconciled checkoutRef={} lineCount={}",
                    PaymentSucceededOrderConfirmationHandler.logReference(reconciliation.checkoutId()),
                    reconciliation.lines().size());
        } catch (RuntimeException failure) {
            checkouts.markCartReconciliationRetry(
                    reconciliation.checkoutId(),
                    "CART_RECONCILIATION_RETRY_REQUIRED",
                    now.plus(Duration.ofSeconds(5)),
                    now);
            log.warn(
                    "Purchased cart reconciliation will retry checkoutRef={} category={}",
                    PaymentSucceededOrderConfirmationHandler.logReference(reconciliation.checkoutId()),
                    failure.getClass().getSimpleName());
        }
    }
}
