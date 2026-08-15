package com.msb.ecom.auth_service.security;

public enum AdminPermission {
    DASHBOARD_READ("admin.dashboard.read"),
    AUDIT_READ("admin.audit.read"),
    BUSINESS_APPLICATION_READ("admin.business.application.read"),
    BUSINESS_APPLICATION_DECIDE("admin.business.application.decide"),
    LISTING_MODERATION_READ("admin.listing.moderation.read"),
    LISTING_MODERATION_CLAIM("admin.listing.moderation.claim"),
    LISTING_MODERATION_RESOLVE("admin.listing.moderation.resolve"),
    LISTING_EDIT("admin.listing.edit"),
    LISTING_REMOVE("admin.listing.remove"),
    USER_READ("admin.user.read"),
    USER_RESTRICT("admin.user.restrict"),
    USER_SUSPEND("admin.user.suspend"),
    USER_BAN("admin.user.ban"),
    USER_REINSTATE("admin.user.reinstate"),
    USER_PII_READ("admin.user.pii.read"),
    BUSINESS_READ("admin.business.read"),
    BUSINESS_RESTRICT("admin.business.restrict"),
    BUSINESS_SUSPEND("admin.business.suspend"),
    BUSINESS_BAN("admin.business.ban"),
    BUSINESS_REINSTATE("admin.business.reinstate"),
    LISTING_SUSPEND("admin.listing.suspend"),
    LISTING_REINSTATE("admin.listing.reinstate"),
    REPORT_READ("admin.report.read"),
    REPORT_ASSIGN("admin.report.assign"),
    REPORT_RESOLVE("admin.report.resolve"),
    ROLE_READ("admin.role.read"),
    ROLE_MANAGE("admin.role.manage");

    private final String id;

    AdminPermission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
