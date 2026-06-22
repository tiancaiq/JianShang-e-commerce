package com.msb.ecom.auth_service.service;

public class BusinessApplicationVersionConflictException extends RuntimeException {

    public BusinessApplicationVersionConflictException() {
        super("The business application changed. Refresh and try again.");
    }
}
