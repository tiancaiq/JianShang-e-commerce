package com.msb.ecom.auth_service.seed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LargeCatalogIdentitySeedContracts {

    private LargeCatalogIdentitySeedContracts() {
    }

    public record Request(
            String namespace,
            long randomSeed,
            int individualSellerCount,
            int businessSellerCount,
            int maxListingAgeDays
    ) { }

    public record IndividualSeller(
            String userId,
            String profileId,
            String displayName,
            String publicCity,
            String publicRegion,
            Instant createdAt
    ) { }

    public record BusinessSeller(
            String ownerUserId,
            String applicationId,
            String businessId,
            String storeId,
            String storeName,
            String storeSlug,
            String segment,
            String publicCity,
            String publicRegion,
            Instant createdAt,
            Instant approvedAt
    ) { }

    public record Response(
            String namespace,
            String reviewerUserId,
            List<IndividualSeller> individualSellers,
            List<BusinessSeller> businesses,
            boolean idempotent
    ) { }

    public record Stats(
            String namespace,
            Map<String, Long> entityCounts,
            long activeIndividualSellers,
            long activeBusinesses,
            long activeStores
    ) { }

    public record ResetResponse(String namespace, long removedEntities) { }
}
