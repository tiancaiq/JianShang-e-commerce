package com.msb.ecom.product_service.reports;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing-reports")
public record ListingReportProperties(
        boolean intakeEnabled,
        boolean retentionPolicyApproved,
        boolean reporterIdentityDisclosureEnabled,
        String abuseHmacSecret,
        String policyVersion,
        String eventTopic,
        Duration idempotencyRetention,
        Duration duplicateWindow,
        Duration contentRetention,
        Duration metadataRetention,
        int hourlyLimit,
        int dailyLimit
) {

    private static final String DISABLED_SECRET = "disabled-only-listing-report-hmac-key";

    @PostConstruct
    void validate() {
        requireExact(idempotencyRetention, Duration.ofDays(90), "Idempotency retention");
        requireExact(duplicateWindow, Duration.ofHours(24), "Duplicate window");
        requireExact(contentRetention, Duration.ofDays(180), "Content retention");
        requireExact(metadataRetention, Duration.ofDays(730), "Metadata retention");
        if (hourlyLimit != 5 || dailyLimit != 20) {
            throw new IllegalArgumentException("Listing report rate limits must remain 5/hour and 20/day.");
        }
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,40}")) {
            throw new IllegalArgumentException("Listing report policy version is invalid.");
        }
        if (eventTopic == null || eventTopic.isBlank() || eventTopic.length() > 200) {
            throw new IllegalArgumentException("Listing report event topic is invalid.");
        }
        if (reporterIdentityDisclosureEnabled) {
            throw new IllegalArgumentException("Reporter identity disclosure is not available in REP-01A.");
        }
        if (intakeEnabled) {
            if (!retentionPolicyApproved) {
                throw new IllegalArgumentException(
                        "Listing report intake requires explicit retention-policy approval.");
            }
            if (abuseHmacSecret == null
                    || abuseHmacSecret.length() < 32
                    || DISABLED_SECRET.equals(abuseHmacSecret)) {
                throw new IllegalArgumentException(
                        "Listing report intake requires a non-default abuse HMAC secret.");
            }
        }
    }

    private void requireExact(Duration actual, Duration required, String name) {
        if (!required.equals(actual)) {
            throw new IllegalArgumentException(name + " must remain " + required + ".");
        }
    }
}
