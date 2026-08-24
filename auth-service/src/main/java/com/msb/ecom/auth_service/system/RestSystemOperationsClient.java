package com.msb.ecom.auth_service.system;

import com.msb.ecom.common.web.correlation.CorrelationId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Component
public class RestSystemOperationsClient implements SystemOperationsClient {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private final Map<String, Endpoint> endpoints;
    private final String token;
    private final Executor executor;

    public RestSystemOperationsClient(RestClient.Builder builder,
            @Value("${service.gateway.url:http://localhost:9000}") String gateway,
            @Value("${service.auth.url:http://localhost:8085}") String auth,
            @Value("${service.product.url:http://localhost:8091}") String product,
            @Value("${service.order.url:http://localhost:8081}") String order,
            @Value("${service.payment.url:http://localhost:8084}") String payment,
            @Value("${service.inventory.url:http://localhost:8082}") String inventory,
            @Value("${service.notification.url:http://localhost:8083}") String notification,
            @Value("${service.chat.url:http://localhost:8092}") String chat,
            @Value("${commerce.internal-service-token}") String token,
            @Qualifier("analyticsExecutor") Executor executor) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requests.setReadTimeout(Duration.ofSeconds(2));
        LinkedHashMap<String, Endpoint> configured = new LinkedHashMap<>();
        configured.put("GATEWAY", endpoint(builder, requests, gateway, "Gateway", false));
        configured.put("AUTH", endpoint(builder, requests, auth, "Auth", false));
        configured.put("PRODUCT", endpoint(builder, requests, product, "Product", true));
        configured.put("ORDER", endpoint(builder, requests, order, "Order", true));
        configured.put("PAYMENT", endpoint(builder, requests, payment, "Payment", true));
        configured.put("INVENTORY", endpoint(builder, requests, inventory, "Inventory", true));
        configured.put("NOTIFICATION", endpoint(builder, requests, notification, "Notification", false));
        configured.put("CHAT", endpoint(builder, requests, chat, "Chat", false));
        this.endpoints = Collections.unmodifiableMap(configured);
        this.token = token;
        this.executor = executor;
    }

    @Override
    public List<SystemContracts.ServiceHealth> health() {
        return fanOut(endpoints.entrySet().stream().toList(),
                entry -> health(entry.getKey(), entry.getValue()));
    }

    @Override
    public List<SystemContracts.SourceSnapshot> snapshots() {
        List<Map.Entry<String, Endpoint>> sources = endpoints.entrySet().stream()
                .filter(entry -> entry.getValue().operationalSource())
                .toList();
        return fanOut(sources, entry -> snapshot(entry.getKey(), entry.getValue()));
    }

    @Override
    public SystemContracts.OwnerAction action(String ownerService, String targetType,
            String targetId, String commandType, boolean dryRun, String reason,
            String idempotencyKey, String correlationId) {
        Endpoint endpoint = endpoints.get(ownerService);
        if (endpoint == null || !endpoint.operationalSource()) {
            throw SystemOperationsException.notFound();
        }
        try {
            SystemContracts.OwnerAction result = endpoint.client().post()
                    .uri("/api/v1/internal/system/operations/actions")
                    .header(TOKEN_HEADER, token)
                    .header("Idempotency-Key", idempotencyKey)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .body(Map.of(
                            "targetType", targetType,
                            "targetId", targetId,
                            "commandType", commandType,
                            "dryRun", dryRun,
                            "reason", reason,
                            "correlationId", correlationId))
                    .retrieve().body(SystemContracts.OwnerAction.class);
            if (result == null) throw SystemOperationsException.unavailable();
            return result;
        } catch (SystemOperationsException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw SystemOperationsException.unavailable();
        }
    }

    private SystemContracts.ServiceHealth health(String key, Endpoint endpoint) {
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        try {
            Map<?, ?> response = endpoint.client().get().uri("/actuator/health")
                    .retrieve().body(Map.class);
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String raw = response == null ? null : String.valueOf(response.get("status"));
            String status = "UP".equalsIgnoreCase(raw) ? "HEALTHY"
                    : "DOWN".equalsIgnoreCase(raw) || "OUT_OF_SERVICE".equalsIgnoreCase(raw)
                    ? "DEGRADED" : "UNKNOWN";
            String summary = "HEALTHY".equals(status) ? "Health endpoint responded normally."
                    : "Service responded with a non-healthy aggregate status.";
            return new SystemContracts.ServiceHealth(key, endpoint.displayName(), status,
                    checkedAt, latency, summary);
        } catch (RuntimeException exception) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new SystemContracts.ServiceHealth(key, endpoint.displayName(), "UNAVAILABLE",
                    checkedAt, latency, "Health endpoint could not be reached within the bounded check.");
        }
    }

    private SystemContracts.SourceSnapshot snapshot(String key, Endpoint endpoint) {
        try {
            SystemContracts.SourceSnapshot result = endpoint.client().get()
                    .uri("/api/v1/internal/system/operations")
                    .header(TOKEN_HEADER, token).retrieve()
                    .body(SystemContracts.SourceSnapshot.class);
            return result == null ? SystemContracts.SourceSnapshot.unavailable(key,
                    "Operational signals returned no data.") : result;
        } catch (RuntimeException exception) {
            return SystemContracts.SourceSnapshot.unavailable(key,
                    "Operational signals are temporarily unavailable.");
        }
    }

    /** Submits the complete bounded owner set before joining while retaining configured result order. */
    private <I, O> List<O> fanOut(List<I> inputs, Function<I, O> operation) {
        List<CompletableFuture<O>> futures = inputs.stream()
                .map(input -> CompletableFuture.supplyAsync(() -> operation.apply(input), executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static Endpoint endpoint(RestClient.Builder builder,
            JdkClientHttpRequestFactory requestFactory, String url, String displayName,
            boolean source) {
        return new Endpoint(builder.clone().requestFactory(requestFactory).baseUrl(url).build(),
                displayName, source);
    }

    private record Endpoint(RestClient client, String displayName, boolean operationalSource) { }
}
