package com.msb.ecom.order_service.security;

public enum AdminOrderPermission {
    READ("admin.order.read"),
    CANCEL("admin.order.cancel"),
    MANAGE("admin.order.manage"),
    USER_PII_READ("admin.user.pii.read");

    private final String id;

    AdminOrderPermission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
