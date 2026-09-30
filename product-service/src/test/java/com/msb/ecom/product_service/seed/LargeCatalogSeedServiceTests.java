package com.msb.ecom.product_service.seed;

import com.msb.ecom.product_service.search.ListingSearchProjectionSyncProperties;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.BatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class LargeCatalogSeedServiceTests {

    @Test
    void stableListingAndProjectionIdsAreDeterministic() {
        assertThat(LargeCatalogSeedService.stableId("catalog|listing|A"))
                .hasSize(26)
                .isEqualTo(LargeCatalogSeedService.stableId("catalog|listing|A"))
                .isNotEqualTo(LargeCatalogSeedService.stableId("catalog|listing|B"));
    }

    @Test
    void productionProfileRefusesSeedBeforeDatabaseMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        var properties = new ListingSearchProjectionSyncProperties(false, 50, 1000, 60, 20, null, null);
        var service = new LargeCatalogSeedService(
                jdbc, environment, properties, "token",
                Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.upsertBatch("token", new BatchRequest(
                "catalog", "00000000000000000000000000", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");

        verifyNoInteractions(jdbc);
    }
}
