package com.msb.ecom.auth_service.service;

public class BusinessApplicationNotFoundException extends RuntimeException {

    public BusinessApplicationNotFoundException() {
        super("Business application was not found.");
    }
}
