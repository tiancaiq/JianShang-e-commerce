package com.msb.ecom.api_gateway.routes;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.*;

import java.net.URI;
import java.util.List;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration(proxyBeanMethods = false)
public class Routes {

        private RequestPredicate returnPaths() {
                return RequestPredicates.path("/api/v1/orders/*/groups/*/return")
                                .or(RequestPredicates.path("/api/v1/orders/*/groups/*/returns"))
                                .or(RequestPredicates.path("/api/v1/businesses/*/orders/*/return"))
                                .or(RequestPredicates.path("/api/v1/businesses/*/orders/*/returns/**"));
        }

        @Bean
        @Order(-2)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "returns", havingValue = "true")
        public RouterFunction<ServerResponse> businessOrderReturnRoute() {
                return route("business_order_returns")
                                .route(returnPaths(), http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(-2)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "returns", havingValue = "false", matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledBusinessOrderReturnRoute() {
                return disabledRoute("disabled_business_order_returns", returnPaths());
        }

        @Value("${service.product.url}")
        private String productServiceUrl;

        @Value("${service.chat.url}")
        private String chatServiceUrl;

        @Value("${service.order.url}")
        private String orderServiceUrl;

        @Value("${service.inventory.url}")
        private String inventoryServiceUrl;

        @Value("${service.payment.url}")
        private String paymentServiceUrl;

        @Value("${service.auth.url}")
        private String authServiceUrl;

        @Value("${service.agent.url}")
        private String agentServiceUrl;

        @Value("${service.notification.url}")
        private String notificationServiceUrl;

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent", havingValue = "true")
        public RouterFunction<ServerResponse> agentServiceRoute() {
                return route("agent_service")
                                .route(RequestPredicates.path("/api/v1/agent/sessions")
                                                .or(RequestPredicates.path("/api/v1/agent/sessions/**"))
                                                .or(RequestPredicates.path("/api/v1/agent/listing-proposals"))
                                                .or(RequestPredicates.path("/api/v1/agent/listing-proposals/**")),
                                                http(agentServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .before(removeRequestHeader("Connection"))
                                .before(removeRequestHeader("Keep-Alive"))
                                .before(removeRequestHeader("Proxy-Authenticate"))
                                .before(removeRequestHeader("Proxy-Authorization"))
                                .before(removeRequestHeader("TE"))
                                .before(removeRequestHeader("Trailer"))
                                .before(removeRequestHeader("Transfer-Encoding"))
                                .before(removeRequestHeader("Upgrade"))
                                .before(removeRequestHeader("HTTP2-Settings"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("agentServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledAgentServiceRoute() {
                return disabledRoute(
                                "disabled_agent_service",
                                RequestPredicates.path("/api/v1/agent/sessions")
                                                .or(RequestPredicates.path("/api/v1/agent/sessions/**"))
                                                .or(RequestPredicates.path("/api/v1/agent/listing-proposals"))
                                                .or(RequestPredicates.path("/api/v1/agent/listing-proposals/**")));
        }

        /**
         * Keeps discovery independently gated so listing Agent activation cannot
         * expose the broader query-first Product/tool boundary.
         */
        @Bean
        @Order(-1)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent-discovery", havingValue = "true")
        public RouterFunction<ServerResponse> agentDiscoveryServiceRoute() {
                RequestPredicate discoveryStreams = RequestPredicates
                                .path("/api/v1/agent/discovery/sessions/*/messages/stream")
                                .or(RequestPredicates.path(
                                                "/api/v1/agent/discovery/sessions/*/messages/*/response-retry/stream"));
                RequestPredicate marketplaceV2Streams = RequestPredicates
                                .path("/api/v1/agent/marketplace-v2/sessions/*/messages/stream")
                                .or(RequestPredicates.path(
                                                "/api/v1/agent/marketplace-v2/sessions/*/messages/*/response-retry/stream"));
                return route("agent_discovery_service")
                                .route(RequestPredicates.path("/api/v1/agent/discovery/**")
                                                .and(discoveryStreams.negate())
                                                .or(RequestPredicates.path("/api/v1/agent/marketplace-v2/**")
                                                        .and(marketplaceV2Streams.negate())),
                                                http(agentServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .before(removeRequestHeader("Connection"))
                                .before(removeRequestHeader("Keep-Alive"))
                                .before(removeRequestHeader("Proxy-Authenticate"))
                                .before(removeRequestHeader("Proxy-Authorization"))
                                .before(removeRequestHeader("TE"))
                                .before(removeRequestHeader("Trailer"))
                                .before(removeRequestHeader("Transfer-Encoding"))
                                .before(removeRequestHeader("Upgrade"))
                                .before(removeRequestHeader("HTTP2-Settings"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("agentDiscoveryServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(-1)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent-discovery", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledAgentDiscoveryServiceRoute() {
                return disabledRoute(
                                "disabled_agent_discovery_service",
                                RequestPredicates.path("/api/v1/agent/discovery/**")
                                                .or(RequestPredicates.path("/api/v1/agent/marketplace-v2/**")));
        }

        @Bean
        @Order(1)
        public RouterFunction<ServerResponse> chatServiceRoute() {
                return route("chat_service")
                                .route(RequestPredicates.path("/api/v1/listings/*/conversations"),
                                                http(chatServiceUrl))
                                .route(RequestPredicates.path("/api/v1/conversations"),
                                                http(chatServiceUrl))
                                .route(RequestPredicates.path("/api/v1/conversations/**"),
                                                http(chatServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("chatServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(2)
        public RouterFunction<ServerResponse> productServiceRoute() {
                return route("product_service")
                                .route(RequestPredicates.path("/api/product/**")
                                                .or(RequestPredicates.path("/api/v1/listings"))
                                                .or(RequestPredicates.path("/api/v1/listings/**"))
                                                .or(RequestPredicates.path("/api/v1/users/me/listings"))
                                                .or(RequestPredicates.path("/api/v1/users/me/liked-listings"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/store/items"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/store/items/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/listings/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/search/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/moderation/**")),
                                                http(productServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("productServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(3)
        public RouterFunction<ServerResponse> publicAuthServiceRoute() {
                return route("public_auth_service")
                                .route(RequestPredicates.path("/api/v1/public/user-avatars/**"),
                                                http(authServiceUrl))
                                .route(RequestPredicates.path("/api/v1/stores/*"),
                                                http(authServiceUrl))
                                .filter(circuitBreaker("publicAuthServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "category-guidance", havingValue = "true")
        public RouterFunction<ServerResponse> categoryGuidanceServiceRoute() {
                return route("category_guidance_service")
                                .route(RequestPredicates.path("/api/v1/admin/categories/**"),
                                                http(productServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("productServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "category-guidance", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledCategoryGuidanceServiceRoute() {
                return disabledRoute("disabled_category_guidance_service",
                                RequestPredicates.path("/api/v1/admin/categories/**"));
        }

        @Bean
        @Order(4)
        public RouterFunction<ServerResponse> publicListingServiceRoute() {
                return route("public_listing_service")
                                .route(RequestPredicates.path("/api/v1/public/listings")
                                                .or(RequestPredicates.path("/api/v1/public/listings/**"))
                                                .or(RequestPredicates.path("/api/v1/public/marketplace/listings/search"))
                                                .or(RequestPredicates.path("/api/v1/public/stores/listings/search"))
                                                .or(RequestPredicates.path("/api/v1/public/listing-media/**")),
                                                http(productServiceUrl))
                                .filter(circuitBreaker("publicListingServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(5)
        public RouterFunction<ServerResponse> publicCategoryServiceRoute() {
                return route("public_category_service")
                                .route(RequestPredicates.path("/api/v1/categories")
                                                .or(RequestPredicates.path("/api/v1/categories/**")),
                                                http(productServiceUrl))
                                .filter(circuitBreaker("publicCategoryServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "cart", havingValue = "true")
        public RouterFunction<ServerResponse> cartOrderServiceRoute() {
                return route("cart_order_service")
                                .route(RequestPredicates.path("/api/v1/cart")
                                                .or(RequestPredicates.path("/api/v1/cart/**")),
                                                http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "cart", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledCartOrderServiceRoute() {
                return disabledRoute("disabled_cart_order_service",
                                RequestPredicates.path("/api/v1/cart")
                                                .or(RequestPredicates.path("/api/v1/cart/**")));
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "checkout", havingValue = "true")
        public RouterFunction<ServerResponse> checkoutOrderServiceRoute() {
                return route("checkout_order_service")
                                .route(RequestPredicates.path("/api/v1/checkouts")
                                                .or(RequestPredicates.path("/api/v1/checkouts/**")),
                                                http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "checkout", havingValue = "true")
        public RouterFunction<ServerResponse> buyerOrderReadRoute() {
                return route("buyer_order_read")
                                .route(RequestPredicates.method(HttpMethod.GET).and(
                                                RequestPredicates.path("/api/v1/orders")
                                                        .or(RequestPredicates.path("/api/v1/orders/*"))),
                                                http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(-2)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "order-cancellation",
                        havingValue = "true")
        public RouterFunction<ServerResponse> orderCancellationRoute() {
                RequestPredicate command = RequestPredicates.method(HttpMethod.POST).and(
                                RequestPredicates.path("/api/v1/orders/*/cancellation-requests"));
                return route("order_cancellation")
                                .route(command, http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(-2)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "order-cancellation",
                        havingValue = "false", matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledOrderCancellationRoute() {
                return disabledRoute("disabled_order_cancellation",
                                RequestPredicates.method(HttpMethod.POST).and(
                                                RequestPredicates.path(
                                                                "/api/v1/orders/*/cancellation-requests")));
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "checkout", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledCheckoutOrderServiceRoute() {
                return disabledRoute("disabled_checkout_order_service",
                                RequestPredicates.path("/api/v1/checkouts")
                                                .or(RequestPredicates.path("/api/v1/checkouts/**"))
                                                .or(RequestPredicates.path("/api/v1/orders"))
                                                .or(RequestPredicates.path("/api/v1/orders/**")));
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "business-orders", havingValue = "true")
        public RouterFunction<ServerResponse> businessOrderServiceRoute() {
                return route("business_order_service")
                                .route(RequestPredicates.method(HttpMethod.GET)
                                                .and(RequestPredicates.path("/api/v1/businesses/*/orders")
                                                        .or(RequestPredicates.path("/api/v1/businesses/*/orders/*"))),
                                                http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "business-order-fulfillment",
                        havingValue = "true")
        public RouterFunction<ServerResponse> businessOrderFulfillmentRoute() {
                RequestPredicate commands = RequestPredicates.method(HttpMethod.POST).and(
                                RequestPredicates.path("/api/v1/businesses/*/orders/*/accept")
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/processing"))
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/shipments"))
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/delivery-demo")));
                return route("business_order_fulfillment")
                                .route(commands, http(orderServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "business-order-fulfillment",
                        havingValue = "false", matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledBusinessOrderFulfillmentRoute() {
                RequestPredicate commands = RequestPredicates.method(HttpMethod.POST).and(
                                RequestPredicates.path("/api/v1/businesses/*/orders/*/accept")
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/processing"))
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/shipments"))
                                                .or(RequestPredicates.path(
                                                                "/api/v1/businesses/*/orders/*/delivery-demo")));
                return disabledRoute("disabled_business_order_fulfillment", commands);
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "business-orders", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledBusinessOrderServiceRoute() {
                return disabledRoute("disabled_business_order_service",
                                RequestPredicates.path("/api/v1/businesses/*/orders")
                                                .or(RequestPredicates.path("/api/v1/businesses/*/orders/**")));
        }

        /**
         * Relays the authenticated buyer notification-center API without accepting
         * browser-supplied actor identity headers.
         */
        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "notifications", havingValue = "true")
        public RouterFunction<ServerResponse> notificationServiceRoute() {
                return route("notification_service")
                                .route(RequestPredicates.path("/api/v1/notifications")
                                                .or(RequestPredicates.path("/api/v1/notifications/**"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/notifications"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/notifications/**")),
                                                http(notificationServiceUrl))
                                .before(removeRequestHeader("X-User-Id"))
                                .before(removeRequestHeader("X-Actor-User-Id"))
                                .before(removeRequestHeader("X-Keycloak-Sub"))
                                .before(removeRequestHeader("X-Roles"))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("notificationServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(0)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "notifications", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledNotificationServiceRoute() {
                return disabledRoute(
                                "disabled_notification_service",
                                RequestPredicates.path("/api/v1/notifications")
                                                .or(RequestPredicates.path("/api/v1/notifications/**"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/notifications"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/notifications/**")));
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "inventory", havingValue = "true")
        public RouterFunction<ServerResponse> inventoryServiceRoute() {
                return route("inventory_service")
                                .route(RequestPredicates.path("/api/v1/businesses/*/inventory")
                                                .or(RequestPredicates.path("/api/v1/businesses/*/inventory/**")),
                                                http(inventoryServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("inventoryServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "inventory", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledInventoryServiceRoute() {
                return disabledRoute("disabled_inventory_service",
                                RequestPredicates.path("/api/v1/businesses/*/inventory")
                                                .or(RequestPredicates.path("/api/v1/businesses/*/inventory/**")));
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "payment", havingValue = "true")
        public RouterFunction<ServerResponse> paymentServiceRoute() {
                return route("payment_service")
                                .route(RequestPredicates.path("/api/payment/**"), http(paymentServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("paymentServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @Order(1)
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "stripe-webhook",
                        havingValue = "true")
        public RouterFunction<ServerResponse> stripePaymentWebhookRoute() {
                return route("stripe_payment_webhook")
                                .POST("/api/v1/webhooks/payments/STRIPE_TEST_V1", http(paymentServiceUrl))
                                .build();
        }

        @Bean
        @Order(10)
        public RouterFunction<ServerResponse> authServiceRoute() {
                return route("auth_service")
                                .route(RequestPredicates.path("/api/v1/users/me")
                                                .or(RequestPredicates.path("/api/v1/users/me/marketplace-capabilities"))
                                                .or(RequestPredicates.path("/api/v1/users/me/avatar/**"))
                                                .or(RequestPredicates.path("/api/v1/individual-seller/**"))
                                                .or(RequestPredicates.path("/api/v1/businesses/me/store-context"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/membership/me"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/store"))
                                                .or(RequestPredicates.path("/api/v1/business-applications/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/me"))
                                                .or(RequestPredicates.path("/api/v1/admin/dashboard-summary"))
                                                .or(RequestPredicates.path("/api/v1/admin/users"))
                                                .or(RequestPredicates.path("/api/v1/admin/users/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/businesses"))
                                                .or(RequestPredicates.path("/api/v1/admin/businesses/**"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/marketplace-capabilities"))
                                                .or(RequestPredicates.path("/api/v1/admin/business-applications/**")),
                                                http(authServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("authServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "payment", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledPaymentServiceRoute() {
                return disabledRoute("disabled_payment_service", RequestPredicates.path("/api/payment/**"));
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "buyer-addresses", havingValue = "true")
        public RouterFunction<ServerResponse> buyerAddressServiceRoute() {
                return route("buyer_address_service")
                                .route(RequestPredicates.path("/api/v1/users/me/addresses")
                                                .or(RequestPredicates.path("/api/v1/users/me/addresses/**")),
                                                http(authServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("authServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        @ConditionalOnProperty(prefix = "msb.gateway.features", name = "buyer-addresses", havingValue = "false",
                        matchIfMissing = true)
        public RouterFunction<ServerResponse> disabledBuyerAddressServiceRoute() {
                return disabledRoute("disabled_buyer_address_service",
                                RequestPredicates.path("/api/v1/users/me/addresses")
                                                .or(RequestPredicates.path("/api/v1/users/me/addresses/**")));
        }

        @Bean
        public RouterFunction<ServerResponse> authWebhookRoute() {
                return route("auth_webhook_service")
                                .route(RequestPredicates.path("/api/v1/webhooks/business-verification"),
                                                http(authServiceUrl))
                                .filter(circuitBreaker("authWebhookServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        public RouterFunction<ServerResponse> fallbackRoute() {
                return route("fallbackRoute")
                                .GET("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                                .body(serviceUnavailable(request)))
                                .POST("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                                .body(serviceUnavailable(request)))
                                .PATCH("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                                .body(serviceUnavailable(request)))
                                .DELETE("/fallbackRoute", request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                                .body(serviceUnavailable(request)))
                                .build();
        }

        private ApiErrorEnvelope serviceUnavailable(ServerRequest request) {
                String correlationId = CorrelationIdFilter.current(request.servletRequest());
                return new ApiErrorEnvelope(new ApiError(
                                "SERVICE_UNAVAILABLE",
                                "Service is temporarily unavailable. Please try again later.",
                                List.of(),
                                correlationId));
        }

        // Owns a quarantined API namespace locally so disabled capabilities cannot fall through to a service.
        private RouterFunction<ServerResponse> disabledRoute(String routeId, RequestPredicate predicate) {
                return route(routeId)
                                .route(predicate, request -> ServerResponse.notFound().build())
                                .build();
        }
}
