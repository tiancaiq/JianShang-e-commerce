package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcPlatformPolicyProvider implements PlatformPolicyProvider {

    private final JdbcClient jdbc;
    private final CheckoutProperties properties;

    public JdbcPlatformPolicyProvider(JdbcClient jdbc, CheckoutProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public Policy current() {
        return jdbc.sql("""
                        SELECT id, source, version_code, shipping_text, cancellation_text, return_text
                        FROM platform_policy_versions
                        WHERE version_code = :version
                        """)
                .param("version", properties.policyVersion())
                .query((rs, rowNum) -> new Policy(
                        rs.getString("id"),
                        rs.getString("source"),
                        rs.getString("version_code"),
                        rs.getString("shipping_text"),
                        rs.getString("cancellation_text"),
                        rs.getString("return_text")))
                .optional()
                .orElseThrow(() -> new CheckoutException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "CHECKOUT_DEPENDENCY_UNAVAILABLE",
                        "Checkout policy is temporarily unavailable."));
    }
}
