package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.search.hybrid.ListingHybridSearchProperties;
import com.msb.ecom.product_service.search.hybrid.ListingConceptRankingProperties;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        ListingSearchProperties.class,
        ListingSearchProjectionSyncProperties.class,
        ListingVectorBackfillProperties.class,
        ListingSearchPromotionProperties.class,
        ListingSearchVectorSyncProperties.class,
        ListingHybridSearchProperties.class,
        ListingConceptRankingProperties.class,
        ListingSearchOperatorProperties.class
})
public class ListingSearchConfig {
}
