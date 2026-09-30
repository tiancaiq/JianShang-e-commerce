package com.msb.ecom.product_service.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LargeCatalogSeedContracts {

    private LargeCatalogSeedContracts() {
    }

    public record ListingInput(
            String listingId,
            String sourceIdentity,
            String sourceDomain,
            String sellerType,
            String individualSellerUserId,
            String businessId,
            String storeId,
            String categoryId,
            String title,
            String description,
            String condition,
            String conditionNotes,
            BigDecimal priceAmount,
            String currency,
            boolean negotiable,
            String sku,
            int quantity,
            String publicCity,
            String publicRegion,
            long visitCount,
            long likeCount,
            Instant createdAt,
            Instant approvedAt,
            Instant publishedAt,
            Instant updatedAt
    ) { }

    public record BatchRequest(
            String namespace,
            String reviewerUserId,
            List<ListingInput> listings
    ) { }

    public record BatchResponse(
            String namespace,
            int received,
            int created,
            int updated,
            long totalSeededListings
    ) { }

    public record InventoryCandidate(
            String listingId,
            String businessId,
            int suggestedOnHand
    ) { }

    public record InventoryCandidatePage(
            List<InventoryCandidate> data,
            PageMetadata page
    ) { }

    public record PageMetadata(String nextCursor, boolean hasMore) { }

    public record Stats(
            String namespace,
            long totalActiveListings,
            long individualActiveListings,
            long businessActiveListings,
            long individualSellerCount,
            double averageListingsPerIndividual,
            double medianListingsPerIndividual,
            long businessSellerCount,
            double averageListingsPerBusiness,
            Map<String, Long> categoryDistribution,
            Map<String, Long> ageDistribution,
            Map<String, Long> engagementDistribution,
            Instant oldestListing,
            Instant newestListing,
            long futureDatedListings,
            long invalidTimestampOrder,
            long invalidPrices,
            long invalidInventory,
            long invalidEngagement,
            long duplicateSeedIdentities,
            long pendingSearchProjectionWork
    ) { }

    public record ResetResponse(String namespace, long removedListings, long queuedSearchDeletes) { }
}
