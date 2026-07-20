package com.msb.ecom.inventory_service.service;

import java.math.BigDecimal;
import java.util.List;

public interface ProductCommerceClient {

    CatalogPage getBusinessItems(
            String businessId,
            String query,
            String status,
            String cursor,
            Integer limit);

    CatalogItem getBusinessItem(String businessId, String listingId);

    record CatalogPage(
            List<CatalogItem> data,
            PageMetadata page
    ) {
    }

    record PageMetadata(
            String nextCursor,
            boolean hasMore
    ) {
    }

    record CatalogItem(
            String listingId,
            String businessId,
            String storeId,
            String sellerType,
            String title,
            String sku,
            BigDecimal priceAmount,
            String currency,
            int quantity,
            String status,
            String publicationSource,
            long version
    ) {
    }
}
