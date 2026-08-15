package com.msb.ecom.product_service.model;

public class BusinessCapabilityRestrictedException extends RuntimeException {

    private final String scope;
    private final String effectiveAction;
    private final String expiresAt;
    private final String supportReference;

    public BusinessCapabilityRestrictedException(
            String scope, String effectiveAction, String expiresAt, String supportReference) {
        super("This business marketplace capability is currently unavailable.");
        this.scope = scope;
        this.effectiveAction = effectiveAction;
        this.expiresAt = expiresAt;
        this.supportReference = supportReference;
    }

    public String scope() { return scope; }
    public String effectiveAction() { return effectiveAction; }
    public String expiresAt() { return expiresAt; }
    public String supportReference() { return supportReference; }
}
