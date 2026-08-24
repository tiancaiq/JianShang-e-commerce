package com.msb.ecom.product_service.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.*;

@RestController
@RequestMapping("/api/v1/internal/admin/analytics")
@RequiredArgsConstructor
public class InternalProductAnalyticsController {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final ProductAnalyticsService service;

    @Value("${commerce.internal-service-token}")
    private String expectedToken;

    @GetMapping("/summary")
    public Summary summary(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        authenticate(token);
        return service.summary(from, to);
    }

    @GetMapping("/trends/listings-created")
    public ListingCreatedTrend listingCreatedTrend(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "DAY") Granularity granularity) {
        authenticate(token);
        return service.listingCreatedTrend(from, to, granularity);
    }

    private void authenticate(String supplied) {
        if (!StringUtils.hasText(expectedToken) || !StringUtils.hasText(supplied)
                || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Internal service authorization is required.");
        }
    }
}
