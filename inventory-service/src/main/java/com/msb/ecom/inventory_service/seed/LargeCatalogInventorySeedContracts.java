package com.msb.ecom.inventory_service.seed;

import java.util.List;

public final class LargeCatalogInventorySeedContracts {

    private LargeCatalogInventorySeedContracts() {
    }

    public record InventoryCandidate(String listingId, String businessId, int suggestedOnHand) { }

    public record BatchRequest(String namespace, List<InventoryCandidate> listings) { }

    public record BatchResponse(String namespace, int received, int initialized, int preserved) { }
}
