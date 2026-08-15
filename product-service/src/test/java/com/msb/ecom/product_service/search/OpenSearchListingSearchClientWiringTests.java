package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OpenSearchListingSearchClientWiringTests {

    @Test
    void springSelectsProductionConstructorAndDefaultOffMakesNoRequest() {
        ListingSearchProperties properties = new ListingSearchProperties(
                "mysql",
                new ListingSearchProperties.OpenSearchProperties(
                        "http://127.0.0.1:1",
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        true));

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(ListingSearchProperties.class, () -> properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(
                    ListingSearchProjectionMetrics.class,
                    () -> new ListingSearchProjectionMetrics(new SimpleMeterRegistry()));
            context.register(OpenSearchListingSearchClient.class);
            context.refresh();

            OpenSearchListingSearchClient client =
                    context.getBean(OpenSearchListingSearchClient.class);
            assertThat(client.enabled()).isFalse();
            assertThatCode(client::ensureIndex).doesNotThrowAnyException();
        }
    }
}
