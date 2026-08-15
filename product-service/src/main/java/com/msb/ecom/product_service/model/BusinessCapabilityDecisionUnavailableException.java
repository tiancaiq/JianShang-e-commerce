package com.msb.ecom.product_service.model;

public class BusinessCapabilityDecisionUnavailableException extends RuntimeException {
    public BusinessCapabilityDecisionUnavailableException() {
        super("Business marketplace capability validation is temporarily unavailable.");
    }
}
