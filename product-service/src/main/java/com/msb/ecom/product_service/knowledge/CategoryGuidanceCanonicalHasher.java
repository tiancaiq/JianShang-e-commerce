package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CategoryGuidanceCanonicalHasher {

    // Produces the language-independent digest used by Product and Agent services.
    public String hash(
            String categoryId,
            String categorySlug,
            String categoryName,
            String language,
            String title,
            String body) {
        String canonical = "{"
                + "\"categoryId\":" + quoted(categoryId) + ","
                + "\"categorySlug\":" + quoted(categorySlug) + ","
                + "\"categoryName\":" + quoted(categoryName) + ","
                + "\"language\":" + quoted(language) + ","
                + "\"title\":" + quoted(title) + ","
                + "\"body\":" + quoted(body)
                + "}";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Category guidance content could not be hashed.", exception);
        }
    }

    private String quoted(String value) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
    }
}
