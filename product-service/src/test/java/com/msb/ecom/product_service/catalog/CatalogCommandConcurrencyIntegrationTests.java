package com.msb.ecom.product_service.catalog;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.msb.ecom.product_service.catalog.CatalogContracts.CategoryStatus.DISABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class CatalogCommandConcurrencyIntegrationTests {
    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String ACTOR_ID = "01CAT000000000000000000001";
    private static final String COMMAND_KEY = "rc02-catalog-disable";

    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog_rc")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysql.start();
    }

    @Autowired CatalogService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean CurrentActorProvider currentActorProvider;
    @MockBean AuthServiceClient authServiceClient;
    @MockBean ListingMediaStorage listingMediaStorage;

    @BeforeEach
    void reset() {
        jdbc.update("delete from catalog_command_idempotency where actor_user_id = ?", ACTOR_ID);
        jdbc.update("delete from catalog_events where category_id = ?", CATEGORY_ID);
        jdbc.update("delete from category_rule_versions where category_id = ?", CATEGORY_ID);
        jdbc.update("""
                update categories set status = 'ACTIVE', current_rule_version = 1, version = 0
                where id = ?
                """, CATEGORY_ID);
        when(currentActorProvider.currentActor()).thenReturn(
                new CurrentActor("rc02-subject", "rc02-token", null, "RC admin", true));
        when(authServiceClient.requirePlatformAdmin("rc02-token"))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(ACTOR_ID, "CATALOG_ADMIN"));
        when(authServiceClient.lookupAdminIdentityLabels(any(), any(), any()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()));
    }

    @Test
    void concurrentSameKeyStatusCommandsMutateOnceAndReplayAfterRestartBoundary() throws Exception {
        var request = new CatalogContracts.ChangeCategoryStatusRequest(
                0, DISABLED, "RC concurrent category safety check");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<CatalogContracts.CategoryDetail>>of(
                    () -> service.changeStatus(CATEGORY_ID, COMMAND_KEY, request),
                    () -> service.changeStatus(CATEGORY_ID, COMMAND_KEY, request));
            var results = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();
            assertThat(results).hasSize(2)
                    .allSatisfy(result -> assertThat(result.category().status()).isEqualTo(DISABLED));
        }

        assertThat(jdbc.queryForObject("select version from categories where id = ?", Long.class, CATEGORY_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from catalog_command_idempotency
                where actor_user_id = ? and operation = 'CATEGORY_STATUS' and idempotency_key = ?
                """, Integer.class, ACTOR_ID, COMMAND_KEY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from catalog_events
                where category_id = ? and event_type = 'CATEGORY_DISABLED'
                """, Integer.class, CATEGORY_ID)).isEqualTo(1);

        var replay = service.changeStatus(CATEGORY_ID, COMMAND_KEY, request);
        assertThat(replay.category().status()).isEqualTo(DISABLED);
        assertThat(replay.category().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from catalog_events
                where category_id = ? and event_type = 'CATEGORY_DISABLED'
                """, Integer.class, CATEGORY_ID)).isEqualTo(1);
    }
}
