package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminUserContracts.CapabilityDecision;

public class UserCapabilityRestrictedException extends RuntimeException {

    private final CapabilityDecision decision;

    public UserCapabilityRestrictedException(CapabilityDecision decision) {
        super("This marketplace capability is currently unavailable for your account.");
        this.decision = decision;
    }

    public CapabilityDecision decision() {
        return decision;
    }
}
