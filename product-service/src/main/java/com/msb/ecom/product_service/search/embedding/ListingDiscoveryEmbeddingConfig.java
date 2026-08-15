package com.msb.ecom.product_service.search.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        ListingDiscoveryEmbeddingProperties.class,
        ListingEmbeddingRequestBackfillProperties.class
})
public class ListingDiscoveryEmbeddingConfig {

    @Bean
    Clock listingDiscoveryEmbeddingClock() {
        return Clock.systemUTC();
    }
}
