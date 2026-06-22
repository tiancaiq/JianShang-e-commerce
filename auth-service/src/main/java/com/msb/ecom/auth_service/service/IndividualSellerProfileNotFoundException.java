package com.msb.ecom.auth_service.service;

public class IndividualSellerProfileNotFoundException extends RuntimeException {

    public IndividualSellerProfileNotFoundException() {
        super("Individual seller profile was not found.");
    }
}
