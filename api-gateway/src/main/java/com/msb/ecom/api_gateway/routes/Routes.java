package com.msb.ecom.api_gateway.routes;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.*;

import java.net.URI;
import java.util.List;

import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration(proxyBeanMethods = false)
public class Routes {

        @Value("${service.product.url}")
        private String productServiceUrl;

        @Value("${service.order.url}")
        private String orderServiceUrl;

        @Value("${service.inventory.url}")
        private String inventoryServiceUrl;

        @Value("${service.payment.url}")
        private String paymentServiceUrl;

        @Value("${service.auth.url}")
        private String authServiceUrl;

        @Bean
        public RouterFunction<ServerResponse> productServiceRoute() {
                return route("product_service")
                                .route(RequestPredicates.path("/api/product/**")
                                                .or(RequestPredicates.path("/api/v1/listings"))
                                                .or(RequestPredicates.path("/api/v1/listings/**")),
                                                http(productServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("productServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
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
        public RouterFunction<ServerResponse> orderServiceRoute() {
                return route("order_service")
                                .route(RequestPredicates.path("/api/order/**"), http(orderServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("orderServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        public RouterFunction<ServerResponse> inventoryServiceRoute() {
                return route("inventory_service")
                                .route(RequestPredicates.path("/api/inventory/**"), http(inventoryServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("inventoryServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        public RouterFunction<ServerResponse> paymentServiceRoute() {
                return route("payment_service")
                                .route(RequestPredicates.path("/api/payment/**"), http(paymentServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("paymentServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
        }

        @Bean
        public RouterFunction<ServerResponse> authServiceRoute() {
                return route("auth_service")
                                .route(RequestPredicates.path("/api/v1/users/**")
                                                .or(RequestPredicates.path("/api/v1/individual-seller/**"))
                                                .or(RequestPredicates.path("/api/v1/businesses/*/membership/me"))
                                                .or(RequestPredicates.path("/api/v1/business-applications/**"))
                                                .or(RequestPredicates.path("/api/v1/admin/business-applications/**")),
                                                http(authServiceUrl))
                                .filter(tokenRelay())
                                .filter(circuitBreaker("authServiceCircuitBreaker",
                                                URI.create("forward:/fallbackRoute")))
                                .build();
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
}
