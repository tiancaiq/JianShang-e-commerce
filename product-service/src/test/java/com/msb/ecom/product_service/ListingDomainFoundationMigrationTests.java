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
        }
    }

    @Test
    void individualListingRequiresIndividualOwnerShapeAndQuantityOne() throws Exception {
        migrate();

        try (Connection connection = connection()) {
            insertCategory(connection);
            insertListing(connection, "01L00000000000000000000001", "INDIVIDUAL",
                    "01U00000000000000000000001", null, null, 1, null, true);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000002", "INDIVIDUAL",
                    "01U00000000000000000000001", "01B00000000000000000000001", null, 1, null, true))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertListing(connection, "01L00000000000000000000003", "INDIVIDUAL",
                    "01U00000000000000000000001", null, null, 2, null, true))
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
}
