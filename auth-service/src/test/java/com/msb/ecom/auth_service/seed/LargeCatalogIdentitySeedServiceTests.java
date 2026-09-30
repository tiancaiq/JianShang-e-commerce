package com.msb.ecom.auth_service.seed;

import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Request;
import com.msb.ecom.auth_service.service.InternalCommerceAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class LargeCatalogIdentitySeedServiceTests {

    @Test
    void stableIdsAreDeterministicAndNamespaceScoped() {
        assertThat(LargeCatalogIdentitySeedService.stableId("catalog-a", "user", 1))
                .hasSize(26)
                .isEqualTo(LargeCatalogIdentitySeedService.stableId("catalog-a", "user", 1))
                .isNotEqualTo(LargeCatalogIdentitySeedService.stableId("catalog-b", "user", 1));
    }

    @Test
    void productionProfileRefusesSeedBeforeDatabaseMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InternalCommerceAuthenticator authenticator = mock(InternalCommerceAuthenticator.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        var service = new LargeCatalogIdentitySeedService(
                jdbc, authenticator, environment,
                Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.ensure("token", new Request(
                "catalog", 7, 10, 2, 548)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");

        verify(authenticator).require("token");
        verifyNoInteractions(jdbc);
    }
}
