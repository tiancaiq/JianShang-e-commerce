package com.msb.ecom.common.testing;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Objects;

public final class MySqlContainerFactory {

    public static final String DEFAULT_IMAGE = "mysql:8.4";

    private MySqlContainerFactory() {
    }

    public static MySQLContainer<?> create(String databaseName) {
        Objects.requireNonNull(databaseName, "databaseName");
        if (!databaseName.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid database name");
        }

        return new MySQLContainer<>(DockerImageName.parse(DEFAULT_IMAGE))
                .withDatabaseName(databaseName)
                .withUsername("test")
                .withPassword("test");
    }
}
