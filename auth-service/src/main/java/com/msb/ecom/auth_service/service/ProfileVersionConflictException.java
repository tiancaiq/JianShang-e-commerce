package com.msb.ecom.auth_service.service;

public class ProfileVersionConflictException extends RuntimeException {

    public ProfileVersionConflictException() {
        super("The profile changed. Refresh and try again.");
    }
}
