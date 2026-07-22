package com.msb.ecom.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class NotificationServiceApplicationTests {

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Test
    void defaultOffApplicationWiresWithoutTransportOrDatabaseAccess() {
    }
}
