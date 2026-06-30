package com.msb.ecom.product_service.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ListingMediaStorageProperties.class)
public class ListingMediaStorageConfig {

    @Bean
    public ListingMediaStorage listingMediaStorage(ListingMediaStorageProperties properties) {
        if ("s3".equalsIgnoreCase(properties.storage())) {
            return new S3ListingMediaStorage(properties);
        }
        return new LocalDemoListingMediaStorage();
    }
}
