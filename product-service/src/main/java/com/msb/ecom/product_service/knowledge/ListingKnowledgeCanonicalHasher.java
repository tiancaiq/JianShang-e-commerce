package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ListingKnowledgeCanonicalHasher {

    // Produces the cross-language source digest over only the approved public listing fields.
    public String hash(ListingDraftResponse listing) {
        String canonical = "{"
                + "\"title\":" + quoted(listing.title()) + ","
                + "\"description\":" + quoted(listing.description()) + ","
                + "\"priceAmount\":" + quoted(listing.priceAmount().stripTrailingZeros().toPlainString()) + ","
                + "\"currency\":" + quoted(listing.currency().toUpperCase(java.util.Locale.ROOT)) + ","
                + "\"publicCity\":" + quoted(listing.publicCity()) + ","
                + "\"publicRegion\":" + quoted(listing.publicRegion())
                + "}";
        try {
            byte[] content = canonical.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Listing knowledge content could not be hashed.", exception);
        }
    }

    private String quoted(String value) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
    }
}
