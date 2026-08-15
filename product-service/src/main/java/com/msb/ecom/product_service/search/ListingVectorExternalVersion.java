package com.msb.ecom.product_service.search;

public final class ListingVectorExternalVersion {

    static final long MAX_LISTING_VERSION = (Long.MAX_VALUE - 2L) / 2L;

    private ListingVectorExternalVersion() {
    }

    // Maps zero-based Product versions to positive OpenSearch versions while reserving vector ordering.
    public static long lexical(long listingVersion) {
        requireValid(listingVersion);
        return Math.addExact(Math.multiplyExact(listingVersion, 2L), 1L);
    }

    public static long vector(long listingVersion) {
        requireValid(listingVersion);
        return Math.addExact(Math.multiplyExact(listingVersion, 2L), 2L);
    }

    private static void requireValid(long listingVersion) {
        if (listingVersion < 0L || listingVersion > MAX_LISTING_VERSION) {
            throw new IllegalArgumentException("Listing version cannot be represented safely.");
        }
    }
}
