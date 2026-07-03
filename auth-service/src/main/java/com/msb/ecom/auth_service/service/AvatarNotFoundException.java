package com.msb.ecom.auth_service.service;

public class AvatarNotFoundException extends RuntimeException {

    public AvatarNotFoundException() {
        super("Avatar image was not found.");
    }
}
