package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.AvatarContent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicAvatarController {

    private final AuthService authService;

    @GetMapping("/user-avatars/{userId}")
    public ResponseEntity<byte[]> publicAvatar(@PathVariable String userId) {
        AvatarContent content = authService.publicAvatar(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(content.bytes());
    }
}
