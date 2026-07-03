package com.msb.ecom.auth_service.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AvatarStorageProperties.class)
public class AvatarStorageConfig {

    @Bean
    public AvatarStorageService avatarStorageService(AvatarStorageProperties properties) {
        if ("s3".equalsIgnoreCase(properties.storage())) {
            return new S3AvatarStorageService(properties);
        }
        return new LocalAvatarStorageService(properties.storageDir(), properties.maxSizeBytes());
    }
}
