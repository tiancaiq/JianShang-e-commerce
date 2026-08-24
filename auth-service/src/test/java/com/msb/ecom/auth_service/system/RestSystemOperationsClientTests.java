package com.msb.ecom.auth_service.system;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RestSystemOperationsClientTests {
    private final RequestOverlap healthOverlap = new RequestOverlap();
    private final RequestOverlap snapshotOverlap = new RequestOverlap();
    private ExecutorService serverExecutor;
    private ExecutorService clientExecutor;
    private HttpServer server;
    private RestSystemOperationsClient client;

    @BeforeEach
    void setUp() throws IOException {
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/actuator/health",
                exchange -> unavailableAfterOverlap(exchange, healthOverlap));
        server.createContext("/api/v1/internal/system/operations",
                exchange -> unavailableAfterOverlap(exchange, snapshotOverlap));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RestSystemOperationsClient(RestClient.builder(),
                baseUrl, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl,
                "internal-test-token", clientExecutor);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (clientExecutor != null) clientExecutor.close();
        if (serverExecutor != null) serverExecutor.close();
    }

    @Test
    void healthFansOutConcurrentlyAndReturnsSafeFallbacksInConfiguredOrder() {
        List<SystemContracts.ServiceHealth> result = client.health();

        assertThat(healthOverlap.maximumActive()).isGreaterThan(1);
        assertThat(result).extracting(SystemContracts.ServiceHealth::serviceKey)
                .containsExactly("GATEWAY", "AUTH", "PRODUCT", "ORDER", "PAYMENT", "INVENTORY",
                        "NOTIFICATION", "CHAT");
        assertThat(result).allMatch(health -> "UNAVAILABLE".equals(health.status()));
    }

    @Test
    void snapshotsFanOutConcurrentlyAndReturnSafeFallbacksInConfiguredOrder() {
        List<SystemContracts.SourceSnapshot> result = client.snapshots();

        assertThat(snapshotOverlap.maximumActive()).isGreaterThan(1);
        assertThat(result).extracting(SystemContracts.SourceSnapshot::ownerService)
                .containsExactly("PRODUCT", "ORDER", "PAYMENT", "INVENTORY");
        assertThat(result).allMatch(snapshot -> !snapshot.available());
    }

    private void unavailableAfterOverlap(HttpExchange exchange, RequestOverlap overlap)
            throws IOException {
        overlap.enter();
        try {
            overlap.awaitPeer();
            byte[] body = "{\"status\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            overlap.exit();
            exchange.close();
        }
    }

    private static final class RequestOverlap {
        private final CountDownLatch firstPair = new CountDownLatch(2);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();

        private void enter() {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            firstPair.countDown();
        }

        private void awaitPeer() {
            try {
                firstPair.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void exit() {
            active.decrementAndGet();
        }

        private int maximumActive() {
            return maximumActive.get();
        }
    }
}
