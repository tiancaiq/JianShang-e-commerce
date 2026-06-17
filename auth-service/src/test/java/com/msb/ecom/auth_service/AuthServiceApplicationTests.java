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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .isEqualTo("V202606161800__create_businesses_and_verification_audit.sql");
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
    }

    @Test
    void platformAdminCanRejectAndApplicantCanSeeDecisionReason() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-reject-owner",
                "Reject LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-reject");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-reject")))
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
    void platformAdminCanRequestMoreBusinessApplicationInformation() throws Exception {
        String applicationId = createAndSubmitBusinessApplication(
                "keycloak-sub-business-info-owner",
                "Info LLC");
        grantPlatformAdmin("keycloak-sub-platform-admin-info");

        mockMvc.perform(post("/api/v1/admin/business-applications/{id}/decision", applicationId)
                        .with(jwt().jwt(token -> token.subject("keycloak-sub-platform-admin-info")))
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
                            phone_verified, status, version, created_at, updated_at
                        )
                        values (
                            '01J00000000000000000000001', 'keycloak-sub-duplicate',
                            'other@example.com', false, 'Other', false, 'ACTIVE', 0,
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
}
