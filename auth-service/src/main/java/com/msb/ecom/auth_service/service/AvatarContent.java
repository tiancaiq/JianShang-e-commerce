package com.msb.ecom.auth_service.service;

public record AvatarContent(
        String contentType,
        byte[] bytes
) {
}
