package com.msb.ecom.product_service.reports;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
final class ListingReportAbuseHasher {

    private final byte[] secret;

    ListingReportAbuseHasher(ListingReportProperties properties) {
        this.secret = properties.abuseHmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    // Produces domain-separated pseudonyms so logs and abuse buckets never persist raw actor IDs.
    String hash(String domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((domain + ':' + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }
}
