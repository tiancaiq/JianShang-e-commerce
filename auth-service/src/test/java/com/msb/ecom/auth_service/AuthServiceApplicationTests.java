package com.msb.ecom.auth_service;

import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthServiceApplicationTests {

    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/identity");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:8181/realms/msb-local");
        registry.add("business.verification.webhook-secret", () -> "test-webhook-secret");
        registry.add("commerce.internal-service-token", () -> "test-commerce-token");
        registry.add("user.avatar.storage", () -> "local-demo");
        registry.add("user.avatar.storage-dir", () -> "target/test-auth-avatars");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void cleanMysqlDatabaseMigratesSuccessfully() {
        assertThat(flyway.info().current().getScript())
                .isEqualTo("V202607192000__add_public_handles_and_trade_demo_users.sql");
        Integer tableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'users'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);
        Integer sellerTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'individual_seller_profiles'",
                Integer.class);
        assertThat(sellerTableCount).isEqualTo(1);
        Integer roleCount = jdbcTemplate.queryForObject(
                "select count(*) from roles where id in ('BUYER', 'INDIVIDUAL_SELLER')",
                Integer.class);
        assertThat(roleCount).isEqualTo(2);
        Integer businessApplicationTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'business_applications'",
                Integer.class);
        assertThat(businessApplicationTableCount).isEqualTo(1);
        Integer businessTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'businesses'",
                Integer.class);
        assertThat(businessTableCount).isEqualTo(1);
        Integer platformAdminRoleCount = jdbcTemplate.queryForObject(
                "select count(*) from roles where id = 'PLATFORM_ADMIN'",
                Integer.class);
        assertThat(platformAdminRoleCount).isEqualTo(1);
        Integer storeTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'stores'",
                Integer.class);
        assertThat(storeTableCount).isEqualTo(1);
        Integer activeBusinessAccountIndexCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'business_applications'
                  and index_name = 'uk_business_applications_one_active_account'
                """, Integer.class);
        assertThat(activeBusinessAccountIndexCount).isEqualTo(1);
        Integer addressTableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'addresses'",
                Integer.class);
        assertThat(addressTableCount).isEqualTo(1);
        Integer defaultAddressConstraintCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where constraint_schema = database()
                  and table_name = 'addresses'
                  and constraint_name = 'uk_addresses_one_default_per_user'
                  and constraint_type = 'UNIQUE'
                """, Integer.class);
        assertThat(defaultAddressConstraintCount).isEqualTo(1);
        Integer publicHandleColumnCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'users'
                  and column_name = 'public_handle'
                  and is_nullable = 'NO'
                """, Integer.class);
        assertThat(publicHandleColumnCount).isEqualTo(1);
        Integer tradeDemoUserCount = jdbcTemplate.queryForObject("""
                select count(*)
                from users
                where public_handle in ('mira-trades', 'jon-buys')
                """, Integer.class);
        assertThat(tradeDemoUserCount).isEqualTo(2);
        String addressDeleteRule = jdbcTemplate.queryForObject("""
                select delete_rule
                from information_schema.referential_constraints
                where constraint_schema = database()
                  and constraint_name = 'fk_addresses_user'
                """, String.class);
        assertThat(addressDeleteRule).isEqualTo("CASCADE");
    }

    @Test
    void buyerCanManageAddressLifecycleAndDefaultPromotion() throws Exception {
        String subject = "keycloak-sub-address-lifecycle";

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Home", "100 Main Street", "Irvine", "us")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.label").value("Home"))
                .andExpect(jsonPath("$.data.countryCode").value("US"))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.version").value(0));

        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                subject);
        String homeId = jdbcTemplate.queryForObject(
                "select id from addresses where user_id = ? and label = 'Home'",
                String.class,
                userId);

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Office", "200 Market Street", "Tustin", "US")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isDefault").value(false));

        String officeId = jdbcTemplate.queryForObject(
                "select id from addresses where user_id = ? and label = 'Office'",
                String.class,
                userId);

        mockMvc.perform(get("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(homeId))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));

        mockMvc.perform(patch("/api/v1/users/me/addresses/{addressId}", officeId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": null,
                                  "city": "  Costa Mesa  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.label").isEmpty())
                .andExpect(jsonPath("$.data.city").value("Costa Mesa"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/v1/users/me/addresses/{addressId}/default", officeId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header(HttpHeaders.IF_MATCH, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.version").value(2));

        Integer defaultCount = jdbcTemplate.queryForObject(
                "select count(*) from addresses where user_id = ? and is_default = true",
                Integer.class,
                userId);
        assertThat(defaultCount).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/users/me/addresses/{addressId}", officeId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header(HttpHeaders.IF_MATCH, 2))
                .andExpect(status().isNoContent());

        Boolean homeDefault = jdbcTemplate.queryForObject(
                "select is_default from addresses where id = ?",
                Boolean.class,
                homeId);
        assertThat(homeDefault).isTrue();
        Integer remainingCount = jdbcTemplate.queryForObject(
                "select count(*) from addresses where user_id = ?",
                Integer.class,
                userId);
        assertThat(remainingCount).isEqualTo(1);
    }

    @Test
    void buyerAddressMutationsEnforceOwnershipAndVersion() throws Exception {
        String ownerSubject = "keycloak-sub-address-owner";
        String otherSubject = "keycloak-sub-address-other";

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(ownerSubject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Home", "10 Owner Way", "Irvine", "US")))
                .andExpect(status().isCreated());
        String addressId = jdbcTemplate.queryForObject("""
                select a.id
                from addresses a
                join users u on u.id = a.user_id
                where u.keycloak_sub = ?
                """, String.class, ownerSubject);

        mockMvc.perform(patch("/api/v1/users/me/addresses/{addressId}", addressId)
                        .with(jwt().jwt(token -> token.subject(otherSubject)))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Tustin\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/users/me/addresses/{addressId}", addressId)
                        .with(jwt().jwt(token -> token.subject(ownerSubject)))
                        .header(HttpHeaders.IF_MATCH, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Tustin\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERSION_CONFLICT"));
    }

    @Test
    void buyerAddressValidationRejectsUnsafeOrMissingFields() throws Exception {
        String subject = "keycloak-sub-address-validation";

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Home", "100 Main\\nStreet", "Irvine", "US")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_INVALID"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("line1"));

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label": "Home",
                                  "recipientName": "Alex Buyer",
                                  "phone": "9495550123",
                                  "line1": "100 Main Street",
                                  "city": "Irvine",
                                  "region": "CA",
                                  "postalCode": "92618",
                                  "countryCode": "US"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_INVALID"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("phone"));
    }

    @Test
    void buyerAddressBookEnforcesTwentyAddressLimit() throws Exception {
        String subject = "keycloak-sub-address-limit";
        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk());
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                subject);
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("""
                    insert into addresses (
                        id, user_id, label, recipient_name, phone, line1, line2,
                        city, region, postal_code, country_code, is_default,
                        version, created_at, updated_at
                    )
                    values (?, ?, ?, 'Limit Buyer', '+19495550123', ?, null,
                            'Irvine', 'CA', '92618', 'US', ?,
                            0, current_timestamp(6), current_timestamp(6))
                    """,
                    "01ADDRLIMIT%015d".formatted(index),
                    userId,
                    "Address " + index,
                    (index + 1) + " Limit Street",
                    index == 0);
        }

        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Overflow", "999 Limit Street", "Irvine", "US")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_BOOK_LIMIT_REACHED"));
    }

    @Test
    void internalCheckoutResolverRequiresTokenAndBuyerOwnership() throws Exception {
        String subject = "keycloak-sub-address-internal";
        mockMvc.perform(post("/api/v1/users/me/addresses")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressRequest("Home", "500 Checkout Road", "Irvine", "US")))
                .andExpect(status().isCreated());
        String buyerId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                subject);
        String addressId = jdbcTemplate.queryForObject(
                "select id from addresses where user_id = ?",
                String.class,
                buyerId);

        mockMvc.perform(get("/api/v1/internal/users/{buyerId}/addresses/{addressId}", buyerId, addressId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_COMMERCE_AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/internal/users/{buyerId}/addresses/{addressId}", buyerId, addressId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerId").value(buyerId))
                .andExpect(jsonPath("$.id").value(addressId))
                .andExpect(jsonPath("$.line1").value("500 Checkout Road"))
                .andExpect(jsonPath("$.email").doesNotExist());

        mockMvc.perform(post("/api/v1/internal/users/checkout-address-resolution")
                        .header("X-Internal-Service-Token", "test-commerce-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"%s","addressId":"%s"}
                                """.formatted(subject, addressId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerId").value(buyerId))
                .andExpect(jsonPath("$.id").value(addressId))
                .andExpect(jsonPath("$.line1").value("500 Checkout Road"));

        mockMvc.perform(post("/api/v1/internal/users/checkout-buyer-resolution")
                        .header("X-Internal-Service-Token", "test-commerce-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"%s"}
                                """.formatted(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerId").value(buyerId));

        mockMvc.perform(post("/api/v1/internal/users/checkout-address-resolution")
                        .header("X-Internal-Service-Token", "test-commerce-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"unknown-subject","addressId":"%s"}
                                """.formatted(addressId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUYER_ADDRESS_NOT_FOUND"));

        mockMvc.perform(get(
                                "/api/v1/internal/users/{buyerId}/addresses/{addressId}",
                                "01WRONGBUYER00000000000000",
                                addressId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUYER_ADDRESS_NOT_FOUND"));

        mockMvc.perform(get(
                                "/api/v1/internal/users/{buyerId}/addresses/{addressId}",
                                "bad-buyer-id",
                                "bad-address-id")
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUYER_ADDRESS_NOT_FOUND"));
    }

    @Test
    void authenticatedSubjectCreatesOneLocalUserMapping() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-001")
                        .claim("email", "Alex@Example.com")
                        .claim("email_verified", true)
                        .claim("name", "Alex Buyer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.keycloakSub").value("keycloak-sub-001"))
                .andExpect(jsonPath("$.data.email").value("alex@example.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Alex Buyer"))
                .andExpect(jsonPath("$.data.publicHandle",
                        matchesPattern("member-[0-7][0-9a-hjkmnp-tv-z]{25}")))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        String firstId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-001");

        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-001")
                        .claim("email", "alex@example.com")
                        .claim("email_verified", true)
                        .claim("preferred_username", "alex"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId));

        Integer mappingCount = jdbcTemplate.queryForObject(
                "select count(*) from users where keycloak_sub = ?",
                Integer.class,
                "keycloak-sub-001");
        assertThat(mappingCount).isEqualTo(1);
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanUpdateAllowedProfileFields() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-profile")
                        .claim("email", "profile@example.com")
                        .claim("name", "Original Name"))))
                .andExpect(status().isOk());

        Long version = jdbcTemplate.queryForObject(
                "select version from users where keycloak_sub = ?",
                Long.class,
                "keycloak-sub-profile");
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select cast(updated_at as char) from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-profile");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-profile")
                                .claim("email", "profile@example.com")
                                .claim("name", "Keycloak Name")))
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "  Alex Profile  ",
                                  "phone": "+19495551234",
                                  "avatarUrl": "https://example.com/avatar.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keycloakSub").value("keycloak-sub-profile"))
                .andExpect(jsonPath("$.data.displayName").value("Alex Profile"))
                .andExpect(jsonPath("$.data.phone").value("+19495551234"))
                .andExpect(jsonPath("$.data.phoneVerified").value(false))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.data.email").value("profile@example.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(version + 1));

        String updatedAtAfter = jdbcTemplate.queryForObject(
                "select cast(updated_at as char) from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-profile");
        assertThat(updatedAtAfter).isNotEqualTo(updatedAtBefore);

        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-profile")
                        .claim("email", "profile@example.com")
                        .claim("name", "Keycloak Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Alex Profile"));
    }

    @Test
    void profileUpdateAcceptsInternalAvatarUrl() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-profile-internal-avatar")
                        .claim("email", "profile-internal-avatar@example.com"))))
                .andExpect(status().isOk());

        Long version = jdbcTemplate.queryForObject(
                "select version from users where keycloak_sub = ?",
                Long.class,
                "keycloak-sub-profile-internal-avatar");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-profile-internal-avatar")
                                .claim("email", "profile-internal-avatar@example.com")))
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Internal Avatar",
                                  "phone": "+19495551234",
                                  "avatarUrl": "/api/v1/public/user-avatars/01KWE72Y1247CX4DE7DGW5X2NQ?v=4"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(
                        "/api/v1/public/user-avatars/01KWE72Y1247CX4DE7DGW5X2NQ?v=4"));
    }

    @Test
    void unauthenticatedProfileUpdateReturns401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alex\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileUpdateRejectsForbiddenInternalFields() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-forbidden")
                                .claim("email", "forbidden@example.com")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Alex",
                                  "emailVerified": true,
                                  "status": "SUSPENDED",
                                  "id": "01J00000000000000000000001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void profileUpdateRequiresCurrentVersionHeader() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-missing-version")
                                .claim("email", "missing-version@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alex\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void profileUpdateRejectsInvalidDisplayNamePhoneAndAvatarUrl() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-invalid-profile")
                                .claim("email", "invalid-profile@example.com")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "   ",
                                  "phone": "9495551234",
                                  "avatarUrl": "ftp://example.com/avatar.png"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleProfileVersionReturns409() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-conflict")
                        .claim("email", "conflict@example.com"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-conflict")
                                .claim("email", "conflict@example.com")))
                        .header(HttpHeaders.IF_MATCH, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Conflict\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void authenticatedUserCanUploadAndDeleteAvatarImage() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-avatar")
                        .claim("email", "avatar@example.com")
                        .claim("name", "Avatar User"))))
                .andExpect(status().isOk());

        Long version = jdbcTemplate.queryForObject(
                "select version from users where keycloak_sub = ?",
                Long.class,
                "keycloak-sub-avatar");
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-avatar");
        String objectKey = "users/" + userId + "/avatar.png";
        byte[] avatarBytes = "avatar-bytes".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/users/me/avatar/upload-request")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-avatar")
                                .claim("email", "avatar@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "avatar.png",
                                  "sizeBytes": 12
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value(objectKey))
                .andExpect(jsonPath("$.data.uploadMethod").value("PUT"))
                .andExpect(jsonPath("$.data.uploadUrl").value("/api/v1/users/me/avatar/content"));

        mockMvc.perform(put("/api/v1/users/me/avatar/content")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-avatar")
                                .claim("email", "avatar@example.com")))
                        .contentType("image/png")
                        .content(avatarBytes))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/me/avatar/confirm")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-avatar")
                                .claim("email", "avatar@example.com")))
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "objectKey": "%s",
                                  "contentType": "image/png",
                                  "sizeBytes": 12
                                }
                                """.formatted(objectKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("/api/v1/public/user-avatars/" + userId + "?v=" + (version + 1)))
                .andExpect(jsonPath("$.data.version").value(version + 1));

        mockMvc.perform(get("/api/v1/public/user-avatars/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(avatarBytes));

        mockMvc.perform(delete("/api/v1/users/me/avatar")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-avatar")
                                .claim("email", "avatar@example.com")))
                        .header(HttpHeaders.IF_MATCH, version + 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(version + 2));

        mockMvc.perform(get("/api/v1/public/user-avatars/{userId}", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AVATAR_NOT_FOUND"));
    }

    @Test
    void avatarUploadRejectsUnsupportedImageTypes() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-avatar-invalid")
                        .claim("email", "avatar-invalid@example.com"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/me/avatar/upload-request")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-avatar-invalid")
                                .claim("email", "avatar-invalid@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/gif",
                                  "fileName": "avatar.gif",
                                  "sizeBytes": 12
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedUserCanActivateIndividualSellerProfile() throws Exception {
        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-seller")
                                .claim("email", "seller@example.com")
                                .claim("email_verified", true)
                                .claim("name", "Seller User")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicCity": "  Irvine  ",
                                  "publicRegion": " CA ",
                                  "termsVersion": "2026-01"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.userId", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.publicCity").value("Irvine"))
                .andExpect(jsonPath("$.data.publicRegion").value("CA"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.completedSalesCount").value(0))
                .andExpect(jsonPath("$.data.termsVersion").value("2026-01"));

        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-seller");
        Integer profileCount = jdbcTemplate.queryForObject(
                "select count(*) from individual_seller_profiles where user_id = ?",
                Integer.class,
                userId);
        assertThat(profileCount).isEqualTo(1);
        Integer roleGrantCount = jdbcTemplate.queryForObject(
                "select count(*) from user_roles where user_id = ? and role_id = 'INDIVIDUAL_SELLER'",
                Integer.class,
                userId);
        assertThat(roleGrantCount).isEqualTo(1);
    }

    @Test
    void duplicateIndividualSellerActivationReturns409() throws Exception {
        String request = """
                {
                  "publicCity": "Irvine",
                  "publicRegion": "CA",
                  "termsVersion": "2026-01"
                }
                """;

        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-seller-duplicate")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-seller-duplicate")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INDIVIDUAL_SELLER_ALREADY_ACTIVE"));
    }

    @Test
    void activeIndividualSellerCanReadCurrentProfile() throws Exception {
        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-seller-read")
                                .claim("email", "seller-read@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicCity": "Irvine",
                                  "publicRegion": "CA",
                                  "termsVersion": "2026-01"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/individual-seller/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-seller-read")
                                .claim("email", "seller-read@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.publicCity").value("Irvine"))
                .andExpect(jsonPath("$.data.publicRegion").value("CA"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.completedSalesCount").value(0))
                .andExpect(jsonPath("$.data.termsVersion").value("2026-01"));
    }

    @Test
    void authenticatedNonSellerCurrentProfileReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/individual-seller/me")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-not-seller")
                                .claim("email", "not-seller@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INDIVIDUAL_SELLER_NOT_FOUND"));
    }

    @Test
    void unauthenticatedCurrentIndividualSellerProfileReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/individual-seller/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanCreateDraftBusinessApplication() throws Exception {
        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token
                                .subject("keycloak-sub-business")
                                .claim("email", "owner@example.com")
                                .claim("name", "Business Owner")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "  Acme Trading LLC  ",
                                  "businessType": "LLC",
                                  "country": "US",
                                  "contactEmail": "Owner@Example.com",
                                  "contactPhone": "+19495551234",
                                  "publicCity": " Irvine ",
                                  "publicRegion": " CA ",
                                  "websiteUrl": "https://example.com",
                                  "description": "  Local marketplace seller  "
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.applicantUserId", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
                .andExpect(jsonPath("$.data.legalName").value("Acme Trading LLC"))
                .andExpect(jsonPath("$.data.businessType").value("LLC"))
                .andExpect(jsonPath("$.data.country").value("US"))
                .andExpect(jsonPath("$.data.contactEmail").value("owner@example.com"))
                .andExpect(jsonPath("$.data.contactPhone").value("+19495551234"))
                .andExpect(jsonPath("$.data.publicCity").value("Irvine"))
                .andExpect(jsonPath("$.data.publicRegion").value("CA"))
                .andExpect(jsonPath("$.data.websiteUrl").value("https://example.com"))
                .andExpect(jsonPath("$.data.description").value("Local marketplace seller"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0));

        Integer applicationCount = jdbcTemplate.queryForObject(
                "select count(*) from business_applications where legal_name = ? and status = 'DRAFT'",
                Integer.class,
                "Acme Trading LLC");
        assertThat(applicationCount).isEqualTo(1);
    }

    @Test
    void duplicateDraftBusinessApplicationReturns409() throws Exception {
        String request = businessApplicationRequest("Duplicate Draft LLC");

        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-duplicate")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-duplicate")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_CONFLICT"));
    }

    @Test
    void applicantCanReadCurrentBusinessApplication() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-current", "Current LLC");

        mockMvc.perform(get("/api/v1/business-applications/me")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-current"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void businessApplicationCanBeReadOnlyByApplicant() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-read", "Readable LLC");

        mockMvc.perform(get("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.legalName").value("Readable LLC"));

        mockMvc.perform(get("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-other"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_NOT_FOUND"));
    }

    @Test
    void applicantCanUpdateDraftBusinessApplicationWithCurrentVersion() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-update", "Before LLC");
        Long version = jdbcTemplate.queryForObject(
                "select version from business_applications where id = ?",
                Long.class,
                applicationId);

        mockMvc.perform(patch("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-update")))
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("After LLC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.legalName").value("After LLC"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(version + 1));
    }

    @Test
    void staleBusinessApplicationVersionReturns409() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-stale", "Stale LLC");

        mockMvc.perform(patch("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-stale")))
                        .header(HttpHeaders.IF_MATCH, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("Stale Updated LLC")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void businessApplicationRejectsInternalAndReviewFields() throws Exception {
        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-forbidden")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "Forbidden LLC",
                                  "businessType": "LLC",
                                  "country": "US",
                                  "contactEmail": "owner@example.com",
                                  "publicCity": "Irvine",
                                  "publicRegion": "CA",
                                  "applicantUserId": "spoofed",
                                  "status": "APPROVED",
                                  "submittedAt": "2026-06-16T12:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void applicantCanSubmitDraftBusinessApplicationWithCurrentVersion() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-submit", "Submit LLC");
        Long version = jdbcTemplate.queryForObject(
                "select version from business_applications where id = ?",
                Long.class,
                applicationId);

        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit")))
                        .header(HttpHeaders.IF_MATCH, version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.submittedAt").exists())
                .andExpect(jsonPath("$.data.version").value(version + 1));

        String status = jdbcTemplate.queryForObject(
                "select status from business_applications where id = ?",
                String.class,
                applicationId);
        assertThat(status).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void staleBusinessApplicationSubmitVersionReturns409() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-submit-stale", "Submit Stale LLC");

        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit-stale")))
                        .header(HttpHeaders.IF_MATCH, 99))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void nonOwnerBusinessApplicationSubmitReturns404() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-submit-owner", "Submit Owner LLC");

        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit-other")))
                        .header(HttpHeaders.IF_MATCH, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_NOT_FOUND"));
    }

    @Test
    void submittedBusinessApplicationCannotBeUpdatedOrSubmittedAgain() throws Exception {
        String applicationId = createBusinessApplication("keycloak-sub-business-submit-once", "Submit Once LLC");

        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit-once")))
                        .header(HttpHeaders.IF_MATCH, 0))
                .andExpect(status().isOk());

        Long version = jdbcTemplate.queryForObject(
                "select version from business_applications where id = ?",
                Long.class,
                applicationId);

        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit-once")))
                        .header(HttpHeaders.IF_MATCH, version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_CONFLICT"));

        mockMvc.perform(patch("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-submit-once")))
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("Should Not Update LLC")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_CONFLICT"));
    }

    @Test
    void unauthenticatedBusinessApplicationSubmitReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", "01J00000000000000000000001")
                        .header(HttpHeaders.IF_MATCH, 0))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedBusinessVerificationWebhookMovesSubmittedApplicationUnderReviewOnce() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-webhook",
                "Webhook LLC");
        String body = """
                {"eventId":"provider-event-001","applicationId":"%s","outcome":"UNDER_REVIEW","reason":"Provider accepted packet"}
                """.formatted(applicationId);

        mockMvc.perform(post("/api/v1/webhooks/business-verification")
                        .header("X-MSB-Signature", "sha256=" + hmacSha256(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));

        mockMvc.perform(post("/api/v1/webhooks/business-verification")
                        .header("X-MSB-Signature", "sha256=" + hmacSha256(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));

        Integer eventCount = jdbcTemplate.queryForObject(
                "select count(*) from business_verification_events where provider_event_id = ?",
                Integer.class,
                "provider-event-001");
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    void businessVerificationWebhookRejectsBadSignature() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-webhook-bad-signature",
                "Webhook Bad Signature LLC");
        String body = """
                {"eventId":"provider-event-bad-signature","applicationId":"%s","outcome":"VERIFICATION_FAILED","reason":"Bad packet"}
                """.formatted(applicationId);

        mockMvc.perform(post("/api/v1/webhooks/business-verification")
                        .header("X-MSB-Signature", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void platformAdminCanApproveBusinessApplicationAndCreatesOwnerMembership() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-approve-owner",
                "Approve LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Business information verified"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBusinessId").exists())
                .andExpect(jsonPath("$.data.decisionReason").value("Business information verified"))
                .andExpect(jsonPath("$.data.decidedAt").exists());

        String ownerUserId = jdbcTemplate.queryForObject(
                "select applicant_user_id from business_applications where id = ?",
                String.class,
                applicationId);
        String businessId = jdbcTemplate.queryForObject(
                "select approved_business_id from business_applications where id = ?",
                String.class,
                applicationId);
        Integer membershipCount = jdbcTemplate.queryForObject("""
                select count(*) from business_memberships
                where business_id = ? and user_id = ? and role = 'OWNER' and status = 'ACTIVE'
                """, Integer.class, businessId, ownerUserId);
        assertThat(membershipCount).isEqualTo(1);
        Integer storeCount = jdbcTemplate.queryForObject("""
                select count(*)
                from stores
                where business_id = ? and slug = ? and name = ? and status = 'ACTIVE'
                """, Integer.class, businessId, "business-" + businessId.toLowerCase(), "Approve LLC");
        assertThat(storeCount).isEqualTo(1);
    }

    @Test
    void approvedBusinessOwnerCannotCreateSecondBusinessAccount() throws Exception {
        approveBusinessApplication(
                "keycloak-sub-business-one-account-owner",
                "One Account LLC",
                "keycloak-sub-business-one-account-admin");

        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-one-account-owner")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("Second Account LLC")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_APPLICATION_CONFLICT"));

        mockMvc.perform(get("/api/v1/business-applications/me")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-one-account-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBusinessId").exists());
    }

    @Test
    void businessOwnerCanReadAndUpdateStoreProfile() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-store-owner",
                "Store Owner LLC",
                "keycloak-sub-store-admin");

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store", businessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessId").value(businessId))
                .andExpect(jsonPath("$.data.slug").value("business-" + businessId.toLowerCase()))
                .andExpect(jsonPath("$.data.name").value("Store Owner LLC"))
                .andExpect(jsonPath("$.data.supportEmail").value("owner@example.com"))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store", businessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-owner")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Store Owner Trading",
                                  "slug": "store-owner-trading",
                                  "description": "Curated business goods",
                                  "logoUrl": "https://example.com/logo.png",
                                  "bannerUrl": null,
                                  "supportEmail": "Help@Example.com",
                                  "supportPhone": "+19495550000"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Store Owner Trading"))
                .andExpect(jsonPath("$.data.slug").value("store-owner-trading"))
                .andExpect(jsonPath("$.data.supportEmail").value("help@example.com"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get("/api/v1/stores/store-owner-trading"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessId").value(businessId))
                .andExpect(jsonPath("$.data.name").value("Store Owner Trading"))
                .andExpect(jsonPath("$.data.publicCity").value("Irvine"))
                .andExpect(jsonPath("$.data.publicRegion").value("CA"));
    }

    @Test
    void approvedBusinessOwnerCanReadCurrentStoreContext() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-store-context-owner",
                "Store Context LLC",
                "keycloak-sub-store-context-admin");

        mockMvc.perform(get("/api/v1/businesses/me/store-context")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-context-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessId").value(businessId))
                .andExpect(jsonPath("$.data.businessLegalName").value("Store Context LLC"))
                .andExpect(jsonPath("$.data.businessStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.membershipRole").value("OWNER"))
                .andExpect(jsonPath("$.data.permissions[0]").value("LISTING_DRAFT_CREATE"))
                .andExpect(jsonPath("$.data.permissions[1]").value("INVENTORY_VIEW"))
                .andExpect(jsonPath("$.data.permissions[2]").value("INVENTORY_MANAGE"))
                .andExpect(jsonPath("$.data.permissions[3]").value("ORDER_VIEW"))
                .andExpect(jsonPath("$.data.permissions[4]").value("ORDER_FULFILL"))
                .andExpect(jsonPath("$.data.permissions[5]").value("ORDER_FINANCE_VIEW"))
                .andExpect(jsonPath("$.data.store.businessId").value(businessId))
                .andExpect(jsonPath("$.data.store.slug").value("business-" + businessId.toLowerCase()))
                .andExpect(jsonPath("$.data.store.name").value("Store Context LLC"));
    }

    @Test
    void internalCommerceEligibilityRequiresServiceTokenAndReturnsCurrentStatuses() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-commerce-eligibility-owner",
                "Commerce Eligibility LLC",
                "keycloak-sub-commerce-eligibility-admin");
        String storeId = jdbcTemplate.queryForObject(
                "select id from stores where business_id = ?",
                String.class,
                businessId);

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility",
                        businessId,
                        storeId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_COMMERCE_AUTH_REQUIRED"));

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility",
                        businessId,
                        storeId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessId").value(businessId))
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.businessStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.storeStatus").value("ACTIVE"));

        jdbcTemplate.update("update stores set status = 'SUSPENDED' where id = ?", storeId);

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility",
                        businessId,
                        storeId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.businessStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.storeStatus").value("SUSPENDED"));
    }

    @Test
    void internalCommerceEligibilityRejectsCrossBusinessStorePair() throws Exception {
        String firstBusinessId = approveBusinessApplication(
                "keycloak-sub-commerce-pair-first-owner",
                "Commerce Pair First LLC",
                "keycloak-sub-commerce-pair-first-admin");
        String secondBusinessId = approveBusinessApplication(
                "keycloak-sub-commerce-pair-second-owner",
                "Commerce Pair Second LLC",
                "keycloak-sub-commerce-pair-second-admin");
        String secondStoreId = jdbcTemplate.queryForObject(
                "select id from stores where business_id = ?",
                String.class,
                secondBusinessId);

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility",
                        firstBusinessId,
                        secondStoreId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_STORE_NOT_FOUND"));
    }

    @Test
    void currentStoreContextIsNullWithoutApprovedBusiness() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/me/store-context")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-context-none"))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":null}"));
    }

    @Test
    void staleBusinessStoreUpdateReturns409() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-store-stale-owner",
                "Store Stale LLC",
                "keycloak-sub-store-stale-admin");

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store", businessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-stale-owner")))
                        .header(HttpHeaders.IF_MATCH, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeUpdateRequest("Store Stale", "store-stale")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void crossBusinessStoreReadReturns403() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-store-real-owner",
                "Store Real Owner LLC",
                "keycloak-sub-store-real-admin");

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store", businessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-other-user"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void duplicateBusinessStoreSlugReturns409() throws Exception {
        String firstBusinessId = approveBusinessApplication(
                "keycloak-sub-store-first-owner",
                "Store First LLC",
                "keycloak-sub-store-first-admin");
        String secondBusinessId = approveBusinessApplication(
                "keycloak-sub-store-second-owner",
                "Store Second LLC",
                "keycloak-sub-store-second-admin");

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store", firstBusinessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-first-owner")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeUpdateRequest("Shared Store", "shared-store")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store", secondBusinessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-store-second-owner")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeUpdateRequest("Shared Store Copy", "shared-store")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_STORE_SLUG_CONFLICT"));
    }

    @Test
    void platformAdminCanRejectAndApplicantCanSeeDecisionReason() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-reject-owner",
                "Reject LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-reject");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-reject")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Unable to verify business information"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-reject-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decisionReason").value("Unable to verify business information"))
                .andExpect(jsonPath("$.data.decidedAt").exists());
    }

    @Test
    void rejectedBusinessApplicantCanStartNewApplication() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-reapply-owner",
                "Rejected First LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-reapply");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-reapply")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Unable to verify business information"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/business-applications/me")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-reapply-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decisionReason").value("Unable to verify business information"));

        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-reapply-owner")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("Reapply LLC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.legalName").value("Reapply LLC"));
    }

    @Test
    void platformAdminCanRequestMoreBusinessApplicationInformation() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-info-owner",
                "Info LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-info");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-info")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REQUEST_INFORMATION",
                                  "reason": "Please clarify ownership documents"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INFORMATION_REQUESTED"))
                .andExpect(jsonPath("$.data.decisionReason").value("Please clarify ownership documents"));
    }

    @Test
    void nonAdminCannotDecideBusinessApplication() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-non-admin-owner",
                "Non Admin LLC");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-not-platform-admin")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Trying without role"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void staleAdminBusinessApplicationDecisionReturns409() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-stale-admin-owner",
                "Stale Admin Decision LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-stale-decision");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-stale-decision")))
                        .header(HttpHeaders.IF_MATCH, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Trying with a stale version"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void missingAdminBusinessApplicationDecisionVersionReturns400() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-missing-version-owner",
                "Missing Version LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-missing-version");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-missing-version")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Missing version"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidAdminBusinessApplicationDecisionVersionReturns400() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-invalid-version-owner",
                "Invalid Version LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-invalid-version");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-invalid-version")))
                        .header(HttpHeaders.IF_MATCH, "latest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Invalid version"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidAdminBusinessApplicationDecisionValueReturns400() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-invalid-decision-owner",
                "Invalid Decision LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-invalid-decision");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-invalid-decision")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "ESCALATE",
                                  "reason": "Unsupported decision"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void blankAdminBusinessApplicationDecisionReasonReturns400() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-blank-reason-owner",
                "Blank Reason LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-blank-reason");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-blank-reason")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonAdminCannotListBusinessApplicationReviewQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-queue-not-admin"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void platformAdminCanListReviewableBusinessApplications() throws Exception {
        String pendingId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-queue-pending",
                "Queue Pending LLC");
        String underReviewId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-queue-under-review",
                "Queue Under Review LLC");
        applyBusinessVerificationOutcome(underReviewId, "UNDER_REVIEW");
        String draftId = createBusinessApplication(
                "keycloak-sub-business-queue-draft",
                "Queue Draft LLC");
        grantPlatformAdmin("keycloak-sub-business-queue-admin");

        mockMvc.perform(get("/api/v1/admin/business-applications")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-queue-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')].legalName".formatted(pendingId))
                        .value("Queue Pending LLC"))
                .andExpect(jsonPath("$.data[?(@.id == '%s')].status".formatted(underReviewId))
                        .value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(draftId)).isEmpty());
    }

    @Test
    void platformAdminCanFilterBusinessApplicationReviewQueueByStatus() throws Exception {
        String pendingId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-queue-filter-pending",
                "Queue Filter Pending LLC");
        String underReviewId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-queue-filter-under-review",
                "Queue Filter Under Review LLC");
        applyBusinessVerificationOutcome(underReviewId, "UNDER_REVIEW");
        grantPlatformAdmin("keycloak-sub-business-queue-filter-admin");

        mockMvc.perform(get("/api/v1/admin/business-applications")
                        .queryParam("status", "UNDER_REVIEW")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-queue-filter-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')].legalName".formatted(underReviewId))
                        .value("Queue Filter Under Review LLC"))
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(pendingId)).isEmpty());
    }

    @Test
    void invalidBusinessApplicationReviewQueueStatusReturns400() throws Exception {
        grantPlatformAdmin("keycloak-sub-business-queue-invalid-status-admin");

        mockMvc.perform(get("/api/v1/admin/business-applications")
                        .queryParam("status", "APPROVED")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-queue-invalid-status-admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void nonAdminCannotReadAdminBusinessApplicationDetail() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-detail-owner",
                "Detail Non Admin LLC");

        mockMvc.perform(get("/api/v1/admin/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-detail-not-admin"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void platformAdminCanReadBusinessApplicationDetail() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-detail-owner-admin",
                "Detail Review LLC");
        grantPlatformAdmin("keycloak-sub-business-detail-admin");

        mockMvc.perform(get("/api/v1/admin/business-applications/{id}", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-business-detail-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.legalName").value("Detail Review LLC"))
                .andExpect(jsonPath("$.data.contactEmail").value("owner@example.com"))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void nonAdminCannotReadAdminDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard-summary")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-dashboard-not-admin"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void platformAdminCanReadPendingBusinessApplicationCount() throws Exception {
        Integer before = jdbcTemplate.queryForObject("""
                select count(*)
                from business_applications
                where status in ('PENDING_VERIFICATION', 'UNDER_REVIEW')
                """, Integer.class);
        createAndSubmitBusinessApplication(
                "keycloak-sub-dashboard-pending",
                "Pending Dashboard LLC");
        grantPlatformAdmin("keycloak-sub-dashboard-admin");

        mockMvc.perform(get("/api/v1/admin/dashboard-summary")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-dashboard-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingBusinessApplications").value(before + 1));
    }

    @Test
    void platformAdminCanReadUserAndBusinessIdentityLabels() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-label-seller")
                        .claim("email", "label-seller@example.com")
                        .claim("name", "Alex Seller"))))
                .andExpect(status().isOk());
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-label-seller");
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-label-business-owner",
                "Label Business LLC");
        grantPlatformAdmin("keycloak-sub-label-business-admin");
        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-label-business-admin")))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Business information verified"
                                }
                                """))
                .andExpect(status().isOk());
        String businessId = jdbcTemplate.queryForObject(
                "select approved_business_id from business_applications where id = ?",
                String.class,
                applicationId);
        grantPlatformAdmin("keycloak-sub-label-admin");

        mockMvc.perform(get("/api/v1/admin/identity-labels")
                        .queryParam("userIds", userId)
                        .queryParam("businessIds", businessId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-label-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users[?(@.id == '%s')].displayName".formatted(userId))
                        .value("Alex Seller"))
                .andExpect(jsonPath("$.data.businesses[?(@.id == '%s')].legalName".formatted(businessId))
                        .value("Label Business LLC"));
    }

    @Test
    void guestCanReadPublicSellerIdentityLabels() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-public-label-seller")
                        .claim("email", "public-label-seller@example.com")
                        .claim("name", "Public Label Seller"))))
                .andExpect(status().isOk());
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-public-label-seller");
        jdbcTemplate.update(
                "update users set avatar_url = ? where id = ?",
                "https://example.com/public-label.png",
                userId);

        mockMvc.perform(get("/api/v1/public/seller-labels").queryParam("userIds", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users[?(@.id == '%s')].displayName".formatted(userId))
                        .value("Public Label Seller"))
                .andExpect(jsonPath("$.data.users[?(@.id == '%s')].avatarUrl".formatted(userId))
                        .value("https://example.com/public-label.png"))
                .andExpect(jsonPath("$.data.users[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.users[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data.users[0].keycloakSub").doesNotExist())
                .andExpect(jsonPath("$.data.users[0].status").doesNotExist());
    }

    @Test
    void guestCanReadPublicBusinessStoreIdentityLabels() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-public-business-label-owner",
                "Public Label Business LLC",
                "keycloak-sub-public-business-label-admin");
        jdbcTemplate.update(
                "update stores set name = ?, slug = ? where business_id = ?",
                "Public Label Store",
                "public-label-store",
                businessId);

        mockMvc.perform(get("/api/v1/public/seller-labels").queryParam("businessIds", businessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businesses[0].id").value(businessId))
                .andExpect(jsonPath("$.data.businesses[0].legalName").value("Public Label Business LLC"))
                .andExpect(jsonPath("$.data.businesses[0].storeName").value("Public Label Store"))
                .andExpect(jsonPath("$.data.businesses[0].storeSlug").value("public-label-store"))
                .andExpect(jsonPath("$.data.businesses[0].publicCity").value("Irvine"))
                .andExpect(jsonPath("$.data.businesses[0].publicRegion").value("CA"))
                .andExpect(jsonPath("$.data.businesses[0].verified").value(true));
    }

    @Test
    void guestCanSearchPublicActiveBusinessStoresByStoreOrLegalName() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-public-store-search-owner",
                "Moon Ribbon LLC",
                "keycloak-sub-public-store-search-admin");
        String storeId = jdbcTemplate.queryForObject(
                "select id from stores where business_id = ?",
                String.class,
                businessId);
        jdbcTemplate.update("update stores set name = ? where id = ?", "Mochi Ribbon Store", storeId);

        mockMvc.perform(get("/api/v1/public/business-stores/search").queryParam("q", "Mochi Ribbon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].businessId").value(businessId))
                .andExpect(jsonPath("$.data[0].storeId").value(storeId))
                .andExpect(jsonPath("$.data[0].storeName").value("Mochi Ribbon Store"));

        mockMvc.perform(get("/api/v1/public/business-stores/search").queryParam("q", "Moon Ribbon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].businessLegalName").value("Moon Ribbon LLC"));
    }

    @Test
    void publicBusinessStoreSearchHidesSuspendedStoresAndBusinesses() throws Exception {
        String businessId = approveBusinessApplication(
                "keycloak-sub-public-store-hidden-owner",
                "Hidden Ribbon LLC",
                "keycloak-sub-public-store-hidden-admin");
        String storeId = jdbcTemplate.queryForObject(
                "select id from stores where business_id = ?",
                String.class,
                businessId);
        jdbcTemplate.update("update stores set name = ? where id = ?", "Hidden Ribbon Store", storeId);

        jdbcTemplate.update("update stores set status = 'SUSPENDED' where id = ?", storeId);
        mockMvc.perform(get("/api/v1/public/business-stores/search").queryParam("q", "Hidden Ribbon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        jdbcTemplate.update("update stores set status = 'ACTIVE' where id = ?", storeId);
        jdbcTemplate.update("update businesses set status = 'SUSPENDED' where id = ?", businessId);
        mockMvc.perform(get("/api/v1/public/business-stores/search").queryParam("businessIds", businessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void publicSellerIdentityLabelsHideUnavailableUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-public-label-suspended")
                        .claim("email", "public-label-suspended@example.com")
                        .claim("name", "Suspended Seller"))))
                .andExpect(status().isOk());
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                "keycloak-sub-public-label-suspended");
        jdbcTemplate.update("update users set status = 'SUSPENDED' where id = ?", userId);

        mockMvc.perform(get("/api/v1/users/public-labels").queryParam("userIds", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users").isEmpty());
    }

    @Test
    void publicSellerIdentityLabelsRejectOversizedBatches() throws Exception {
        String[] userIds = IntStream.range(0, 51)
                .mapToObj(index -> String.format("01L%023d", index))
                .toArray(String[]::new);

        mockMvc.perform(get("/api/v1/users/public-labels").queryParam("userIds", userIds))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedBusinessApplicationCreateReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/business-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest("Unauthenticated LLC")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void individualSellerActivationRejectsExactAddressAndInternalFields() throws Exception {
        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-seller-forbidden")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicCity": "Irvine",
                                  "publicRegion": "CA",
                                  "streetAddress": "123 Exact Street",
                                  "status": "ACTIVE",
                                  "userId": "spoofed",
                                  "termsVersion": "2026-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void individualSellerActivationRequiresCurrentTermsVersion() throws Exception {
        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-seller-old-terms")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicCity": "Irvine",
                                  "publicRegion": "CA",
                                  "termsVersion": "2025-01"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedIndividualSellerActivationReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/individual-seller/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicCity": "Irvine",
                                  "publicRegion": "CA",
                                  "termsVersion": "2026-01"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerTokenReturns401() throws Exception {
        when(jwtDecoder.decode("invalid-token"))
                .thenThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validBearerTokenReachesProtectedServiceCodeAndMapsIdentityUser() throws Exception {
        when(jwtDecoder.decode("valid-keycloak-token"))
                .thenReturn(Jwt.withTokenValue("valid-keycloak-token")
                        .header("alg", "RS256")
                        .subject("keycloak-sub-real-token")
                        .claim("email", "Real.Token@Example.com")
                        .claim("email_verified", true)
                        .claim("name", "Real Token")
                        .build());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-keycloak-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keycloakSub").value("keycloak-sub-real-token"))
                .andExpect(jsonPath("$.data.email").value("real.token@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("Real Token"));

        Integer mappingCount = jdbcTemplate.queryForObject(
                "select count(*) from users where keycloak_sub = ?",
                Integer.class,
                "keycloak-sub-real-token");
        assertThat(mappingCount).isEqualTo(1);
    }

    @Test
    void uniqueKeycloakSubPreventsDuplicateMappings() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token
                        .subject("keycloak-sub-duplicate")
                        .claim("email", "duplicate@example.com"))))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into users (
                            id, keycloak_sub, email, email_verified, display_name,
                            public_handle, phone_verified, status, version, created_at, updated_at
                        )
                        values (
                            '01J00000000000000000000001', 'keycloak-sub-duplicate',
                            'other@example.com', false, 'Other', 'other-user', false, 'ACTIVE', 0,
                            current_timestamp(6), current_timestamp(6)
                        )
                        """))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void clientHeadersCannotSpoofTheAuthenticatedSubject() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("X-User-Id", "spoofed-user")
                        .header("X-Keycloak-Sub", "spoofed-subject")
                        .with(jwt().jwt(token -> token
                                .subject("real-keycloak-sub")
                                .claim("email", "real@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keycloakSub").value("real-keycloak-sub"));

        Integer spoofedCount = jdbcTemplate.queryForObject(
                "select count(*) from users where keycloak_sub in ('spoofed-subject', 'spoofed-user')",
                Integer.class);
        assertThat(spoofedCount).isZero();
    }

    private String createBusinessApplication(String subject, String legalName) throws Exception {
        mockMvc.perform(post("/api/v1/business-applications")
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessApplicationRequest(legalName)))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject(
                "select id from business_applications where legal_name = ?",
                String.class,
                legalName);
    }

    private String createAndSubmitBusinessApplication(String subject, String legalName) throws Exception {
        String applicationId = createBusinessApplication(subject, legalName);
        mockMvc.perform(post("/api/v1/business-applications/{id}/submit", applicationId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header(HttpHeaders.IF_MATCH, 0))
                .andExpect(status().isOk());
        return applicationId;
    }

    private String approveBusinessApplication(String ownerSubject, String legalName, String adminSubject) throws Exception {
        String applicationId = createAndSubmitBusinessApplication(ownerSubject, legalName);
        grantPlatformAdmin(adminSubject);
        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject(adminSubject)))
                        .header(HttpHeaders.IF_MATCH, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Business information verified"
                                }
                                """))
                .andExpect(status().isOk());
        return jdbcTemplate.queryForObject(
                "select approved_business_id from business_applications where id = ?",
                String.class,
                applicationId);
    }

    private String storeUpdateRequest(String name, String slug) {
        return """
                {
                  "name": "%s",
                  "slug": "%s",
                  "description": "Updated store profile",
                  "logoUrl": null,
                  "bannerUrl": null,
                  "supportEmail": "support@example.com",
                  "supportPhone": "+19495550000"
                }
                """.formatted(name, slug);
    }

    private void applyBusinessVerificationOutcome(String applicationId, String outcome) throws Exception {
        String body = """
                {"eventId":"provider-event-%s","applicationId":"%s","outcome":"%s","reason":"Provider queued review"}
                """.formatted(applicationId, applicationId, outcome);
        mockMvc.perform(post("/api/v1/webhooks/business-verification")
                        .header("X-MSB-Signature", "sha256=" + hmacSha256(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void grantPlatformAdmin(String subject) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk());
        String userId = jdbcTemplate.queryForObject(
                "select id from users where keycloak_sub = ?",
                String.class,
                subject);
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_id, granted_by, granted_at)
                values (?, 'PLATFORM_ADMIN', null, ?)
                on duplicate key update granted_at = granted_at
                """, userId, Timestamp.from(Instant.now()));
    }

    private String hmacSha256(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String businessApplicationRequest(String legalName) {
        return """
                {
                  "legalName": "%s",
                  "businessType": "LLC",
                  "country": "US",
                  "contactEmail": "owner@example.com",
                  "contactPhone": "+19495551234",
                  "publicCity": "Irvine",
                  "publicRegion": "CA",
                  "websiteUrl": "https://example.com",
                  "description": "Local seller"
                }
                """.formatted(legalName);
    }

    private String addressRequest(
            String label,
            String line1,
            String city,
            String countryCode) {
        return """
                {
                  "label": "%s",
                  "recipientName": "Alex Buyer",
                  "phone": "+19495550123",
                  "line1": "%s",
                  "line2": null,
                  "city": "%s",
                  "region": "CA",
                  "postalCode": "92618",
                  "countryCode": "%s"
                }
                """.formatted(label, line1, city, countryCode);
    }
}
