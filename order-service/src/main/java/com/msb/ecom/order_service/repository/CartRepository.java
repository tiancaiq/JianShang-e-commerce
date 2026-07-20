package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartStoredItem;

public interface CartRepository {

    CartDocument get(String userId);

    CartDocument upsert(String userId, CartStoredItem item);

    CartDocument replaceQuantity(String userId, String listingId, int quantity);

    CartDocument remove(String userId, String listingId);

    void clear(String userId);
}
