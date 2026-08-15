package com.msb.ecom.product_service.model;

public class UserCapabilityDecisionUnavailableException extends RuntimeException {
    public UserCapabilityDecisionUnavailableException() {
        super("Marketplace capability validation is temporarily unavailable.");
    }
}
