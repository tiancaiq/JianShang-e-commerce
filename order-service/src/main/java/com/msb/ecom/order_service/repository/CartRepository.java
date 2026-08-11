package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.model.PurchasedCartReconciliation;

import java.util.Optional;

public interface CartRepository {

    CartDocument get(String userId);

    Optional<CartDocument> replay(String userId, String idempotencyKey, String requestHash);

    CartDocument upsert(
            String userId,
            CartStoredItem item,
            long expectedVersion,
            String idempotencyKey,
            String requestHash);

    CartDocument replaceQuantity(
            String userId,
            String listingId,
            int quantity,
            long expectedVersion,
            String idempotencyKey,
            String requestHash);

    CartDocument remove(
            String userId,
            String listingId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash);

    CartDocument clear(
            String userId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash);

    CartDocument reconcilePurchased(PurchasedCartReconciliation reconciliation);
}
