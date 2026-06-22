package com.msb.ecom.auth_service.service;

public class BusinessMembershipNotFoundException extends RuntimeException {

    public BusinessMembershipNotFoundException() {
        super("Business membership was not found.");
    }
}
