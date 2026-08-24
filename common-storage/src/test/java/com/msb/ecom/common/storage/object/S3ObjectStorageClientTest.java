package com.msb.ecom.common.storage.object;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ObjectStorageClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsObjectsWithTheAuthenticatedSdkClientRatherThanAPresignedUrl() throws IOException {
        byte[] body = "image-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, body);
        });

        try (S3ObjectStorageClient client = client()) {
            assertArrayEquals(body, client.readObject("listings/demo/image.png"));
        }

        assertEquals("/listing-media-local/listings/demo/image.png", requestPath.get());
        assertNull(rawQuery.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=test-access/"));
    }

    @Test
    void mapsForbiddenSdkReadsWithoutReturningTheStorageResponseBody() throws IOException {
        server = server(exchange -> respond(
                exchange,
                403,
                "<Error><Code>SignatureDoesNotMatch</Code><Message>sensitive provider detail</Message></Error>"
                        .getBytes(StandardCharsets.UTF_8)));

        try (S3ObjectStorageClient client = client()) {
            ObjectStorageAccessDeniedException exception = assertThrows(
                    ObjectStorageAccessDeniedException.class,
                    () -> client.readObject("listings/demo/image.png"));
            assertEquals("Stored object could not be read.", exception.getMessage());
        }
    }

    @Test
    void verifiesKnownObjectsWithAnAuthenticatedHeaderRequest() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = server(exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().set("Content-Length", "11");
            exchange.sendResponseHeaders(200, -1);
        });

        try (S3ObjectStorageClient client = client()) {
            client.verifyReadable("listings/demo/known.png");
        }

        assertEquals("HEAD", method.get());
        assertEquals("/listing-media-local/listings/demo/known.png", requestPath.get());
        assertNull(rawQuery.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=test-access/"));
    }

    private S3ObjectStorageClient client() {
        return new S3ObjectStorageClient(new S3ObjectStorageSettings(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "us-east-1",
                "listing-media-local",
                "test-access",
                "test-secret",
                true,
                Duration.ofMinutes(15),
                "test"));
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
