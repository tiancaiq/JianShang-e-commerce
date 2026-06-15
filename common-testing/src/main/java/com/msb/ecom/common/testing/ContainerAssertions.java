package com.msb.ecom.common.testing;

import org.junit.jupiter.api.Assertions;
import org.testcontainers.containers.GenericContainer;

public final class ContainerAssertions {

    private ContainerAssertions() {
    }

    public static void assertRunning(GenericContainer<?> container) {
        Assertions.assertNotNull(container, "container");
        Assertions.assertTrue(container.isRunning(), "container must be running");
    }
}
