package com.msb.ecom.auth_service;

import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LocalCartDemoFixtureApplicationTests {

    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/identity");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:8181/realms/msb-local");
        registry.add("commerce.internal-service-token", () -> "test-commerce-token");
        registry.add("demo.cart-second-business-fixture.enabled", () -> "true");
        registry.add("user.avatar.storage", () -> "local-demo");
        registry.add("user.avatar.storage-dir", () -> "target/test-auth-avatars");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repeatedCallsRestoreOneApprovedBusinessStoreAndOwnerMembership() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/internal/demo-fixtures/cart/second-business")
                            .header("X-Internal-Service-Token", "test-commerce-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.businessId").value("01KZCARTB00000000000000002"))
                    .andExpect(jsonPath("$.storeId").value("01KZCARTB00000000000000003"))
                    .andExpect(jsonPath("$.storeSlug").value("harbor-cart-supply"))
                    .andExpect(jsonPath("$.verified").value(true));
        }

        String persistedSubject = "44444444-4444-4444-8444-444444444444";
        mockMvc.perform(post("/api/v1/internal/demo-fixtures/cart/second-business")
                        .header("X-Internal-Service-Token", "test-commerce-token")
                        .header("X-Local-Demo-Owner-Subject", persistedSubject))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/internal/demo-fixtures/cart/second-business")
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk());

        assertThat(count("businesses", "id", "01KZCARTB00000000000000002")).isEqualTo(1);
        assertThat(count("stores", "id", "01KZCARTB00000000000000003")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from business_memberships
                where business_id = '01KZCARTB00000000000000002'
                  and user_id = '01D00000000000000000000002'
                  and role = 'OWNER' and status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
        assertThat(count("business_verification_events", "id", "01KZCARTB00000000000000004")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select keycloak_sub from users where id = '01D00000000000000000000002'",
                String.class)).isEqualTo(persistedSubject);
    }

    @Test
    void invalidServiceCredentialCreatesNoFixtureRecords() throws Exception {
        Integer recordsBefore = count("businesses", "id", "01KZCARTB00000000000000002");

        mockMvc.perform(post("/api/v1/internal/demo-fixtures/cart/second-business")
                        .header("X-Internal-Service-Token", "wrong-token"))
                .andExpect(status().isForbidden());

        assertThat(count("businesses", "id", "01KZCARTB00000000000000002"))
                .isEqualTo(recordsBefore);
    }

    private Integer count(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?",
                Integer.class,
                value);
    }
}
