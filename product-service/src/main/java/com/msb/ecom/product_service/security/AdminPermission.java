package com.msb.ecom.product_service.security;

public enum AdminPermission {
    DASHBOARD_READ("admin.dashboard.read"),
    AUDIT_READ("admin.audit.read"),
    LISTING_MODERATION_READ("admin.listing.moderation.read"),
    LISTING_MODERATION_CLAIM("admin.listing.moderation.claim"),
    LISTING_MODERATION_RESOLVE("admin.listing.moderation.resolve"),
    LISTING_EDIT("admin.listing.edit"),
    LISTING_REMOVE("admin.listing.remove"),
    LISTING_SUSPEND("admin.listing.suspend"),
    LISTING_REINSTATE("admin.listing.reinstate");

    private final String id;

    AdminPermission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
