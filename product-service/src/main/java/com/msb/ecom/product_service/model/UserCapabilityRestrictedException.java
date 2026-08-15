package com.msb.ecom.product_service.model;

public class UserCapabilityRestrictedException extends RuntimeException {

    private final String scope;
    private final String effectiveAction;
    private final String expiresAt;
    private final String supportReference;

    public UserCapabilityRestrictedException(
            String scope,
            String effectiveAction,
            String expiresAt,
            String supportReference) {
        super("This marketplace capability is currently unavailable for your account.");
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
