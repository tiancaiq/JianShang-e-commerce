package com.msb.ecom.payment_service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PaymentMigrationUpgradeIntegrationTests {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payment_upgrade")
            .withUsername("payment")
            .withPassword("payment");

    @Test
    void migratesFromPreAdminFinanceSchemaToCurrentWithoutRepairOrOutOfOrderExecution() {
        var dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("7").load().migrate();

        var upgraded = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(upgraded.migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = database()
                  and table_name in ('payment_admin_refund_commands', 'payment_refunds')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from flyway_schema_history
                where success = true and version in ('8', '9', '10')
                """, Integer.class)).isEqualTo(3);
    }
}
