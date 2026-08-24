package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface PaymentIntentClient {

    PaymentIntent create(String idempotencyKey, String correlationId, Command command);

    default PaymentIntent get(String paymentIntentId, String buyerId) {
        throw new UnsupportedOperationException("Payment lookup is not configured.");
    }

    default DemoCompletion completeDemo(
            String paymentIntentId,
            String buyerId,
            String actionReference,
            String correlationId) {
        throw new UnsupportedOperationException("Demo payment completion is not configured.");
    }

    record Command(
            String checkoutId,
            long checkoutVersion,
            String checkoutSnapshotHash,
            String buyerId,
            List<String> businessIds,
            BigDecimal amount,
            String currency,
            Instant expiresAt
    ) {
        public Command {
            businessIds = List.copyOf(businessIds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentIntent(
            String id,
            String checkoutId,
            long checkoutVersion,
            String buyerId,
            List<String> businessIds,
            BigDecimal amount,
            String currency,
            String provider,
            String providerReference,
            String status,
            long version,
            Instant expiresAt,
            ProviderAction providerAction,
            SafeError error
    ) {
        public PaymentIntent {
            businessIds = businessIds == null ? List.of() : List.copyOf(businessIds);
        }
    }

    record ProviderAction(String type, String reference) {
    }

    record SafeError(String code, String message) {
    }

    record DemoCompletion(
            String eventId,
            String paymentIntentId,
            String status,
            String outcome,
            boolean replayed
    ) {
    }
}
