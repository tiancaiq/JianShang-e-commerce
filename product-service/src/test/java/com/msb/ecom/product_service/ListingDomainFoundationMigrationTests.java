package com.msb.ecom.product_service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ListingDomainFoundationMigrationTests {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Test
    void catalogListingSchemaMigrates() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            assertThat(tableCount(connection, "categories")).isEqualTo(1);
            assertThat(tableCount(connection, "category_attribute_definitions")).isEqualTo(1);
            assertThat(tableCount(connection, "listings")).isEqualTo(1);
            assertThat(tableCount(connection, "listing_attributes")).isEqualTo(1);
            assertThat(tableCount(connection, "listing_moderation_decisions")).isEqualTo(1);
            assertThat(tableCount(connection, "moderation_cases")).isEqualTo(1);
            assertThat(tableCount(connection, "listing_knowledge_versions")).isEqualTo(1);
            assertThat(tableCount(connection, "category_guidance_versions")).isEqualTo(1);
            assertThat(tableCount(connection, "outbox_events")).isEqualTo(1);
        }
    }

    @Test
    void listingReviewCasesCaptureSellerSnapshotAndSubmitter() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000021", "INDIVIDUAL",
                    "01U00000000000000000000021", null, null, 1, null, true);
            insertListingModerationCase(connection, "01M00000000000000000000021",
                    "01L00000000000000000000021", "INDIVIDUAL",
                    "01U00000000000000000000021", null, "01U00000000000000000000021", "OPEN", null);

            assertThat(caseColumnValue(connection, "01M00000000000000000000021", "case_type"))
                    .isEqualTo("LISTING_REVIEW");
            assertThat(caseColumnValue(connection, "01M00000000000000000000021", "subject_seller_type"))
                    .isEqualTo("INDIVIDUAL");
            assertThat(caseColumnValue(connection, "01M00000000000000000000021", "subject_individual_seller_user_id"))
                    .isEqualTo("01U00000000000000000000021");
            assertThat(caseColumnValue(connection, "01M00000000000000000000021", "subject_business_id"))
                    .isNull();
            assertThat(caseColumnValue(connection, "01M00000000000000000000021", "submitted_by_user_id"))
                    .isEqualTo("01U00000000000000000000021");
        }
    }

    @Test
    void listingReviewCasesSupportBusinessSellerSnapshotAndAssignmentVersion() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000031", "BUSINESS",
                    null, "01B00000000000000000000031", null, 4, "SKU-31", false);
            insertListingModerationCase(connection, "01M00000000000000000000031",
                    "01L00000000000000000000031", "BUSINESS",
                    null, "01B00000000000000000000031", "01U00000000000000000000031",
                    "CLAIMED", "01A00000000000000000000031");

            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "subject_seller_type"))
                    .isEqualTo("BUSINESS");
            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "subject_individual_seller_user_id"))
                    .isNull();
            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "subject_business_id"))
                    .isEqualTo("01B00000000000000000000031");
            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "submitted_by_user_id"))
                    .isEqualTo("01U00000000000000000000031");
            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "assigned_admin_user_id"))
                    .isEqualTo("01A00000000000000000000031");
            assertThat(caseColumnValue(connection, "01M00000000000000000000031", "version"))
                    .isEqualTo(0L);
        }
    }

    @Test
    void listingReviewCasesRejectInvalidTypeStatusPriorityAndSellerShape() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000041", "INDIVIDUAL",
                    "01U00000000000000000000041", null, null, 1, null, true);

            assertThatThrownBy(() -> insertModerationCase(connection, "01M00000000000000000000041",
                    "LISTING_REPORT", "01L00000000000000000000041", "INDIVIDUAL",
                    "01U00000000000000000000041", null, "01U00000000000000000000041",
                    "OPEN", "NORMAL", null, null))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertModerationCase(connection, "01M00000000000000000000042",
                    "LISTING_REVIEW", "01L00000000000000000000041", "INDIVIDUAL",
                    "01U00000000000000000000041", null, "01U00000000000000000000041",
                    "IN_PROGRESS", "NORMAL", null, null))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertModerationCase(connection, "01M00000000000000000000043",
                    "LISTING_REVIEW", "01L00000000000000000000041", "INDIVIDUAL",
                    "01U00000000000000000000041", null, "01U00000000000000000000041",
                    "OPEN", "CRITICAL", null, null))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertModerationCase(connection, "01M00000000000000000000044",
                    "LISTING_REVIEW", "01L00000000000000000000041", "INDIVIDUAL",
                    "01U00000000000000000000041", "01B00000000000000000000041",
                    "01U00000000000000000000041", "OPEN", "NORMAL", null, null))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void listingReviewCasesExposeOpenAndAssignedLookupIndexes() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            assertThat(indexExists(connection, "moderation_cases", "idx_moderation_cases_open_listing_reviews"))
                    .isTrue();
            assertThat(indexExists(connection, "moderation_cases", "idx_moderation_cases_assigned_admin"))
                    .isTrue();
        }
    }

    @Test
    void listingReviewCasesPreventDuplicateActiveCasesForSameListing() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000051", "INDIVIDUAL",
                    "01U00000000000000000000051", null, null, 1, null, true);
            insertListingModerationCase(connection, "01M00000000000000000000051",
                    "01L00000000000000000000051", "INDIVIDUAL",
                    "01U00000000000000000000051", null, "01U00000000000000000000051", "OPEN", null);

            assertThatThrownBy(() -> insertListingModerationCase(connection, "01M00000000000000000000052",
                    "01L00000000000000000000051", "INDIVIDUAL",
                    "01U00000000000000000000051", null, "01U00000000000000000000051", "CLAIMED",
                    "01A00000000000000000000051"))
                    .isInstanceOf(SQLException.class);

            resolveModerationCase(connection, "01M00000000000000000000051");
            insertListingModerationCase(connection, "01M00000000000000000000053",
                    "01L00000000000000000000051", "INDIVIDUAL",
                    "01U00000000000000000000051", null, "01U00000000000000000000051", "OPEN", null);

            assertThat(activeCaseCount(connection, "01L00000000000000000000051")).isEqualTo(1);
        }
    }

    @Test
    void individualListingRequiresIndividualOwnerShapeAndPositiveQuantity() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000001", "INDIVIDUAL",
                    "01U00000000000000000000001", null, null, 1, null, true);
            insertListing(connection, "01L00000000000000000000002", "INDIVIDUAL",
                    "01U00000000000000000000001", null, null, 2, null, true);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000003", "INDIVIDUAL",
                    "01U00000000000000000000001", "01B00000000000000000000001", null, 1, null, true))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000004", "INDIVIDUAL",
                    "01U00000000000000000000001", null, null, 0, null, true))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void businessListingRequiresBusinessOwnerAndNonNegotiableQuantity() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000011", "BUSINESS",
                    null, "01B00000000000000000000001", "01S00000000000000000000001", 5, "SKU-1", false);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000012", "BUSINESS",
                    "01U00000000000000000000001", "01B00000000000000000000001", null, 5, "SKU-2", false))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000013", "BUSINESS",
                    null, "01B00000000000000000000001", null, 5, "SKU-3", true))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog")
                .cleanDisabled(false)
                .load()
                .clean();

        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog")
                .load()
                .migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private int tableCount(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                where table_schema = database() and table_name = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private Object caseColumnValue(Connection connection, String caseId, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select " + columnName + " from moderation_cases where id = ?")) {
            statement.setString(1, caseId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getObject(1);
            }
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = ? and index_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private int activeCaseCount(Connection connection, String listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from moderation_cases
                where subject_listing_id = ?
                  and case_type = 'LISTING_REVIEW'
                  and status in ('OPEN', 'CLAIMED')
                """)) {
            statement.setString(1, listingId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void resolveModerationCase(Connection connection, String caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update moderation_cases
                set status = 'RESOLVED',
                    resolved_at = ?,
                    updated_at = ?
                where id = ?
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setString(3, caseId);
            statement.executeUpdate();
        }
    }

    private void insertCategory(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values ('01C00000000000000000000001', 'general', 'General', 'ACTIVE', 0, ?, ?)
                on duplicate key update name = name
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.executeUpdate();
        }
    }

    private void insertListing(
            Connection connection,
            String id,
            String sellerType,
            String individualSellerUserId,
            String businessId,
            String storeId,
            int quantity,
            String sku,
            boolean negotiable) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, price_amount, currency,
                    negotiable, sku, quantity, public_city, public_region, status,
                    moderation_status, version, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, '01K00000000000000000000001',
                    'Listing title', 'Listing description', 'GOOD', ?, 'USD',
                    ?, ?, ?, 'Irvine', 'CA', 'DRAFT', 'NOT_SUBMITTED', 0, ?, ?)
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, id);
            statement.setString(2, sellerType);
            statement.setString(3, individualSellerUserId);
            statement.setString(4, businessId);
            statement.setString(5, storeId);
            statement.setBigDecimal(6, BigDecimal.valueOf(25));
            statement.setBoolean(7, negotiable);
            statement.setString(8, sku);
            statement.setInt(9, quantity);
            statement.setTimestamp(10, now);
            statement.setTimestamp(11, now);
            statement.executeUpdate();
        }
    }

    private void insertListingModerationCase(
            Connection connection,
            String caseId,
            String listingId,
            String sellerType,
            String individualSellerUserId,
            String businessId,
            String submittedByUserId,
            String status,
            String assignedAdminUserId) throws SQLException {
        insertModerationCase(connection, caseId, "LISTING_REVIEW", listingId, sellerType,
                individualSellerUserId, businessId, submittedByUserId, status, "NORMAL",
                assignedAdminUserId, null);
    }

    private void insertModerationCase(
            Connection connection,
            String caseId,
            String caseType,
            String listingId,
            String sellerType,
            String individualSellerUserId,
            String businessId,
            String submittedByUserId,
            String status,
            String priority,
            String assignedAdminUserId,
            Instant resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id,
                    submitted_by_user_id, status, priority, assigned_admin_user_id,
                    version, created_at, updated_at, resolved_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, caseId);
            statement.setString(2, caseType);
            statement.setString(3, listingId);
            statement.setString(4, sellerType);
            statement.setString(5, individualSellerUserId);
            statement.setString(6, businessId);
            statement.setString(7, submittedByUserId);
            statement.setString(8, status);
            statement.setString(9, priority);
            statement.setString(10, assignedAdminUserId);
            statement.setTimestamp(11, now);
            statement.setTimestamp(12, now);
            statement.setTimestamp(13, resolvedAt == null ? null : Timestamp.from(resolvedAt));
            statement.executeUpdate();
        }
    }
}
