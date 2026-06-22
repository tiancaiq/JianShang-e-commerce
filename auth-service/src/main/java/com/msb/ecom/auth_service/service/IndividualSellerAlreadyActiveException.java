package com.msb.ecom.auth_service.service;

public class IndividualSellerAlreadyActiveException extends RuntimeException {

    public IndividualSellerAlreadyActiveException() {
        super("Individual seller profile already exists.");
    }
}
