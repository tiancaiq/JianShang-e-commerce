package com.msb.ecom.product_service.catalog;

import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ListingCatalogPolicyServiceIntegrationTests {
    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String LISTING_ID = "01LCT000000000000000000001";
    private static final String USER_ID = "01UCT000000000000000000001";
    private static final String TEXT_ID = "01ACT000000000000000000001";
    private static final String NUMBER_ID = "01ACN000000000000000000001";
    private static final String BOOLEAN_ID = "01ACB000000000000000000001";
    private static final String ENUM_ID = "01ACE000000000000000000001";
    private static final String MULTI_ID = "01ACM000000000000000000001";

    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog_policy")
            .withUsername("catalog")
            .withPassword("catalog");

    static { mysql.start(); }

    @Autowired ListingCatalogPolicyService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean AuthServiceClient authServiceClient;
    @MockBean ListingMediaStorage listingMediaStorage;

    @BeforeEach
    void configurePolicy() {
        cleanupRows();
        jdbc.update("""
                update categories set status='ACTIVE',seller_eligibility='BOTH',
                  listing_creation_allowed=true,listing_submission_allowed=true,current_rule_version=7
                where id=?
                """, CATEGORY_ID);
        insertAttribute(TEXT_ID, "model_name", "TEXT", true, "{\"minLength\":2,\"maxLength\":40}", 0);
        insertAttribute(NUMBER_ID, "weight_kg", "NUMBER", false, "{\"minimum\":0,\"maximum\":100}", 1);
        insertAttribute(BOOLEAN_ID, "refurbished", "BOOLEAN", false, "{}", 2);
        insertAttribute(ENUM_ID, "color", "ENUM", false, "{}", 3);
        insertAttribute(MULTI_ID, "features", "MULTI_ENUM", false, "{}", 4);
        insertOption("01OPT000000000000000000001", ENUM_ID, "black");
        insertOption("01OPT000000000000000000002", MULTI_ID, "wifi");
        insertOption("01OPT000000000000000000003", MULTI_ID, "bluetooth");
    }

    @AfterEach
    void restoreCategory() {
        cleanupRows();
        jdbc.update("""
                update categories set status='ACTIVE',seller_eligibility='BOTH',
                  listing_creation_allowed=true,listing_submission_allowed=true,current_rule_version=1
                where id=?
                """, CATEGORY_ID);
    }

    @Test
    void validatesAndPersistsEverySupportedAttributeType() {
        var validated = service.validateDraft(CATEGORY_ID, ListingSellerType.INDIVIDUAL, 7L, Map.of(
                "model_name", "  Roadster  ",
                "weight_kg", new BigDecimal("12.5"),
                "refurbished", false,
                "color", "black",
                "features", List.of("wifi", "bluetooth", "wifi")));

        assertThat(validated.ruleVersion()).isEqualTo(7);
        assertThat(validated.attributes()).hasSize(5);
        insertListing("DRAFT");
        service.replaceAttributes(LISTING_ID, validated, Instant.now());

        assertThat(service.values(LISTING_ID).attributes())
                .containsEntry("model_name", "Roadster")
                .containsEntry("weight_kg", new BigDecimal("12.5000"))
                .containsEntry("refurbished", false)
                .containsEntry("color", "black")
                .containsEntry("features", List.of("wifi", "bluetooth"));
        assertThatCode(() -> service.validateSubmission(LISTING_ID, ListingSellerType.INDIVIDUAL))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsStaleRulesMissingRequiredValuesAndDisabledEnumOptions() {
        assertCatalogCode(() -> service.validateDraft(
                CATEGORY_ID, ListingSellerType.INDIVIDUAL, 6L, Map.of()), "CATEGORY_RULE_CHANGED");

        insertListing("DRAFT");
        assertCatalogCode(() -> service.validateSubmission(
                LISTING_ID, ListingSellerType.INDIVIDUAL), "CATEGORY_ATTRIBUTE_REQUIRED");

        jdbc.update("update category_attribute_options set status='DISABLED' where attribute_id=?", ENUM_ID);
        assertCatalogCode(() -> service.validateDraft(CATEGORY_ID, ListingSellerType.INDIVIDUAL, 7L,
                Map.of("model_name", "Roadster", "color", "black")), "CATEGORY_ATTRIBUTE_INVALID");
    }

    @Test
    void enforcesSellerEligibilityAndLeavesExistingActiveListingsUnchangedWhenCategoryIsDisabled() {
        jdbc.update("update categories set seller_eligibility='BUSINESS' where id=?", CATEGORY_ID);
        assertCatalogCode(() -> service.validateDraft(
                CATEGORY_ID, ListingSellerType.INDIVIDUAL, 7L, Map.of()), "CATEGORY_SELLER_INELIGIBLE");

        insertListing("ACTIVE");
        jdbc.update("update categories set status='DISABLED',listing_creation_allowed=false where id=?", CATEGORY_ID);
        assertCatalogCode(() -> service.validateDraft(
                CATEGORY_ID, ListingSellerType.BUSINESS, 7L, Map.of()), "CATEGORY_CREATION_DISABLED");
        assertThat(jdbc.queryForObject("select status from listings where id=?", String.class, LISTING_ID))
                .isEqualTo("ACTIVE");
    }

    private void insertAttribute(String id, String key, String type, boolean required,
                                 String validation, int order) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into category_attribute_definitions(
                  id,category_id,attribute_key,label,description,data_type,required,searchable,filterable,
                  allowed_values_json,validation_json,display_order,status,version,created_at,updated_at)
                values(?,?,?,?,'integration policy field',?,?,false,false,null,?,?,'ACTIVE',0,?,?)
                """, id, CATEGORY_ID, key, key, type, required, validation, order, now, now);
    }

    private void insertOption(String id, String attributeId, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into category_attribute_options(
                  id,attribute_id,option_value,label,display_order,status,version,created_at,updated_at)
                values(?,?,?,?,0,'ACTIVE',0,?,?)
                """, id, attributeId, value, value, now, now);
    }

    private void insertListing(String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into listings(
                  id,seller_type,individual_seller_user_id,business_id,store_id,category_id,category_rule_version,
                  title,description,condition_code,condition_notes,price_amount,currency,negotiable,sku,quantity,
                  public_city,public_region,payment_preferences_json,delivery_preferences_json,status,
                  moderation_status,published_at,version,created_at,updated_at)
                values(?,'INDIVIDUAL',?,null,null,?,7,'Catalog policy test','Safe integration fixture','GOOD',null,
                  10,'USD',false,null,1,'Irvine','CA',null,null,?,? ,null,0,?,?)
                """, LISTING_ID, USER_ID, CATEGORY_ID, status,
                "ACTIVE".equals(status) ? "APPROVED" : "NOT_SUBMITTED", now, now);
    }

    private void cleanupRows() {
        jdbc.update("delete from listing_attributes where listing_id=?", LISTING_ID);
        jdbc.update("delete from listings where id=?", LISTING_ID);
        jdbc.update("delete from category_attribute_options where attribute_id in (?,?,?,?,?)",
                TEXT_ID, NUMBER_ID, BOOLEAN_ID, ENUM_ID, MULTI_ID);
        jdbc.update("delete from category_attribute_definitions where id in (?,?,?,?,?)",
                TEXT_ID, NUMBER_ID, BOOLEAN_ID, ENUM_ID, MULTI_ID);
    }

    private void assertCatalogCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CatalogException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }
}
