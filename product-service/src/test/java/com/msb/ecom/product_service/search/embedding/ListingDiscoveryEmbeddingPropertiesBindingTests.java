package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.search.ListingSearchConfig;
import com.msb.ecom.product_service.search.ListingSearchProjectionMetrics;
import com.msb.ecom.product_service.search.ListingSearchProjectionSyncProperties;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ListingDiscoveryEmbeddingPropertiesBindingTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(
                    ListingDiscoveryEmbeddingConfig.class,
                    ListingSearchConfig.class,
                    SearchClientTestConfig.class);

    @Test
    void bindsAllCanonicalFieldsFromExplicitProperties() {
        contextRunner
                .withPropertyValues(
                        "listing.search.embedding.request-enabled=true",
                        "listing.search.embedding.source-enabled=true",
                        "listing.search.embedding.result-enabled=true",
                        "listing.search.embedding.topic=listing-discovery-explicit-v1",
                        "listing.search.embedding.backfill.commands-enabled=true",
                        "listing.search.embedding.backfill.status-enabled=true",
                        "listing.search.embedding.backfill.page-size=25",
                        "listing.search.embedding.backfill.max-listings=500",
                        "listing.search.embedding.backfill.lease-duration=PT3M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ListingDiscoveryEmbeddingProperties properties =
                            context.getBean(ListingDiscoveryEmbeddingProperties.class);
                    assertThat(properties.requestEnabled()).isTrue();
                    assertThat(properties.sourceEnabled()).isTrue();
                    assertThat(properties.resultEnabled()).isTrue();
                    assertThat(properties.topic()).isEqualTo("listing-discovery-explicit-v1");
                    ListingEmbeddingRequestBackfillProperties backfill =
                            context.getBean(ListingEmbeddingRequestBackfillProperties.class);
                    assertThat(backfill.commandsEnabled()).isTrue();
                    assertThat(backfill.statusEnabled()).isTrue();
                    assertThat(backfill.pageSize()).isEqualTo(25);
                    assertThat(backfill.maxListings()).isEqualTo(500);
                    assertThat(backfill.leaseDuration()).isEqualTo(java.time.Duration.ofMinutes(3));
                });
    }

    @Test
    void applicationDefaultsKeepEveryEmbeddingAndSearchGateDisabledWithoutNetworkWork() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ListingDiscoveryEmbeddingProperties embedding =
                    context.getBean(ListingDiscoveryEmbeddingProperties.class);
            ListingSearchProperties search = context.getBean(ListingSearchProperties.class);
            ListingSearchProjectionSyncProperties projectionSync =
                    context.getBean(ListingSearchProjectionSyncProperties.class);
            ListingEmbeddingRequestBackfillProperties backfill =
                    context.getBean(ListingEmbeddingRequestBackfillProperties.class);
            OpenSearchListingSearchClient client = context.getBean(OpenSearchListingSearchClient.class);

            assertThat(embedding.requestEnabled()).isFalse();
            assertThat(embedding.sourceEnabled()).isFalse();
            assertThat(embedding.resultEnabled()).isFalse();
            assertThat(embedding.topic()).isEqualTo("listing-discovery-embedding-request-v1");
            assertThat(search.openSearchEnabled()).isFalse();
            assertThat(projectionSync.enabled()).isFalse();
            assertThat(backfill.commandsEnabled()).isFalse();
            assertThat(backfill.statusEnabled()).isFalse();
            assertThat(backfill.pageSize()).isEqualTo(50);
            assertThat(backfill.maxListings()).isEqualTo(100_000);
            assertThatCode(client::ensureIndex).doesNotThrowAnyException();
        });
    }

    @Test
    void invalidTopicStillFailsConfigurationBinding() {
        contextRunner
                .withPropertyValues("listing.search.embedding.topic=unsafe topic")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Listing discovery embedding event topic is invalid.");
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    static class SearchClientTestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ListingSearchProjectionMetrics listingSearchProjectionMetrics(MeterRegistry meterRegistry) {
            return new ListingSearchProjectionMetrics(meterRegistry);
        }

        @Bean
        OpenSearchListingSearchClient openSearchListingSearchClient(
                ListingSearchProperties properties,
                ObjectMapper objectMapper,
                ListingSearchProjectionMetrics metrics) {
            return new OpenSearchListingSearchClient(properties, objectMapper, metrics);
        }
    }
}
