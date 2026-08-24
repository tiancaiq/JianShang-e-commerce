package com.msb.ecom.auth_service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.msb.ecom.auth_service.support.SupportContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SupportPersistenceIntegrationTests {
    private static final String REQUESTER = id(1);
    private static final String ADMIN_A = id(2);
    private static final String ADMIN_B = id(3);
    @Container static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static JdbcTemplate jdbc;
    static SupportRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        repository = new SupportRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        insertUser(REQUESTER, "support-requester");
        insertUser(ADMIN_A, "support-admin-a");
        insertUser(ADMIN_B, "support-admin-b");
    }

    @Test
    void cleanMigrationCreatesDedicatedSupportTablesAndPermissions() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables where table_schema=database() and table_name in (
                  'support_tickets','support_messages','support_internal_notes','support_ticket_links',
                  'support_escalations','support_ticket_events','support_command_idempotency')
                """, Integer.class);
        Integer permissions = jdbc.queryForObject("select count(*) from admin_permissions where id like 'admin.support.%'",
                Integer.class);
        assertThat(tables).isEqualTo(7);
        assertThat(permissions).isEqualTo(5);
    }

    @Test
    void concurrentClaimHasOneWinnerAndResolvedTicketRejectsFurtherWork() throws Exception {
        Instant now = Instant.parse("2026-08-21T03:00:00Z");
        String ticket = id(10);
        repository.insertTicket(row(ticket, now));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var commands = java.util.List.<Callable<Boolean>>of(
                    () -> repository.claim(ticket, 0, ADMIN_A, now),
                    () -> repository.claim(ticket, 0, ADMIN_B, now));
            long winners = executor.invokeAll(commands).stream().filter(result -> {
                try { return result.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count();
            assertThat(winners).isEqualTo(1);
        }
        var claimed = repository.ticket(ticket, false).orElseThrow();
        assertThat(repository.resolve(ticket, claimed.version(), claimed.assignedAdmin(),
                ResolutionCode.ISSUE_RESOLVED, "Issue resolved", now)).isTrue();
        var resolved = repository.ticket(ticket, false).orElseThrow();
        assertThat(resolved.status()).isEqualTo(Status.RESOLVED);
        assertThat(repository.touchForAdmin(ticket, resolved.version(), resolved.assignedAdmin(),
                Status.UNDER_REVIEW, now)).isFalse();
    }

    @Test
    void detailMessagesAndInboxOrderLinksLoadWithoutPerRowIdentityLookups() {
        Instant now = Instant.parse("2026-08-21T04:00:00Z");
        String first = id(20);
        String second = id(21);
        String firstOrder = id(30);
        String secondOrder = id(31);
        repository.insertTicket(row(first, now));
        repository.insertTicket(row(second, now));
        repository.insertMessage(new SupportRepository.MessageRow(id(40), first, "SUPPORT_ADMIN", ADMIN_A,
                "A safe support response", "message-key", "message-hash", now, "ignored-on-write"));
        repository.insertLink(new SupportRepository.LinkRow(first, TargetType.ORDER, firstOrder, RelationType.RELATED,
                "Order " + firstOrder, "/admin/orders/" + firstOrder, "ADMIN", ADMIN_A, now));
        repository.insertLink(new SupportRepository.LinkRow(second, TargetType.ORDER, secondOrder, RelationType.RELATED,
                "Order " + secondOrder, "/admin/orders/" + secondOrder, "ADMIN", ADMIN_A, now));

        assertThat(repository.messages(first)).singleElement().satisfies(message ->
                assertThat(message.authorName()).isEqualTo("support-admin-a"));
        assertThat(repository.firstOrderIds(List.of(first, second)))
                .containsEntry(first, firstOrder)
                .containsEntry(second, secondOrder);
    }

    private static SupportRepository.TicketRow row(String ticket, Instant now) {
        return new SupportRepository.TicketRow(ticket, REQUESTER, Category.ACCOUNT_HELP, "Account assistance",
                "I need assistance updating an account setting safely.", Status.OPEN, Priority.MEDIUM, null,
                null, null, null, "fingerprint-" + ticket, "correlation", 0, now, now,
                "Support requester", "ACTIVE", "support@example.test");
    }

    private static void insertUser(String id, String handle) {
        Instant now = Instant.parse("2026-08-21T02:00:00Z");
        jdbc.update("""
                insert into users(id,keycloak_sub,email,email_verified,display_name,public_handle,phone,
                  phone_verified,avatar_url,status,account_type,version,created_at,updated_at)
                values(?,?,?,true,?,?,null,false,null,'ACTIVE','HUMAN',0,?,?)
                """, id, "keycloak-" + id, handle + "@example.test", handle, handle,
                Timestamp.from(now), Timestamp.from(now));
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }
}
