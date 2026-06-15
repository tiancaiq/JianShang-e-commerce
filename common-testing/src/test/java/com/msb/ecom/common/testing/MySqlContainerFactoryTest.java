package com.msb.ecom.common.testing;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlContainerFactoryTest {

    @Test
    void configuresContainerWithoutStartingDocker() {
        try (MySQLContainer<?> container = MySqlContainerFactory.create("marketplace_test")) {
            assertEquals("marketplace_test", container.getDatabaseName());
            assertEquals("test", container.getUsername());
            assertFalse(container.isRunning());
        }
    }

    @Test
    void rejectsUnsafeDatabaseName() {
        assertThrows(IllegalArgumentException.class,
                () -> MySqlContainerFactory.create("marketplace-test;drop"));
    }
}
