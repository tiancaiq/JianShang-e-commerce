package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ListingDiscoveryEmbeddingSourceBuilder {

    public static final String DOCUMENT_SCHEMA_VERSION = "MARKETPLACE_LISTING_DISCOVERY_V2";
    public static final String INPUT_SCHEMA_VERSION = "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1";
    public static final String NORMALIZER_VERSION = "NFKC_WHITESPACE_V1";
    public static final String REDACTOR_VERSION = "PUBLIC_CONTACT_REDACTION_V1";
    public static final String LANGUAGE = "und";
    public static final String PROVIDER = "openai";
    public static final String MODEL = "text-embedding-3-small";
    public static final int DIMENSIONS = 1536;
    public static final int MAX_EMBEDDING_TEXT_LENGTH = 8_000;

    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 5_000;
    private static final int MAX_CATEGORY_NAME_LENGTH = 160;
    private static final int MAX_CATEGORY_SLUG_LENGTH = 120;
    private static final int MAX_PUBLIC_LOCATION_LENGTH = 120;
    private static final int MAX_IMAGE_URL_LENGTH = 2_048;
    private static final Set<Integer> REMOVED_BIDI_CODE_POINTS = Set.of(
            0x061C,
            0x200E,
            0x200F,
            0x202A,
            0x202B,
            0x202C,
            0x202D,
            0x202E,
            0x2066,
            0x2067,
            0x2068,
            0x2069);
    private static final Pattern EMAIL = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]{1,64}@[\\p{L}\\p{N}.-]{1,190}\\.[\\p{L}]{2,63}");
    private static final Pattern URL = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:https?://|www\\.)\\S+");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\+?\\d[\\d().\\-\\s]{5,}\\d(?![\\p{L}\\p{N}])");
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;

    public ListingDiscoveryEmbeddingSourceBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Builds the only public text permitted to cross from Product to the Agent embedding boundary.
    public ListingDiscoveryEmbeddingSource build(
            PublicListingResponse listing,
            long listingVersion,
            List<PublicListingImageResponse> publicImages) {
        if (listing == null || listingVersion < 0) {
            throw new IllegalArgumentException("Listing discovery source identity is invalid.");
        }
        String title = normalizedField("Title", listing.title(), MAX_TITLE_LENGTH);
        String categoryName = normalizedField(
                "Category name", listing.categoryName(), MAX_CATEGORY_NAME_LENGTH);
        String categorySlug = normalizedField(
                "Category slug", listing.categorySlug(), MAX_CATEGORY_SLUG_LENGTH);
        String description = normalizedField(
                "Description", listing.description(), MAX_DESCRIPTION_LENGTH);

        String embeddingText = String.join("\n",
                "TITLE",
                redact(title),
                "CATEGORY",
                redact(categoryName),
                redact(categorySlug),
                "DESCRIPTION",
                redact(description));
        if (embeddingText.length() > MAX_EMBEDDING_TEXT_LENGTH) {
            throw new IllegalArgumentException("Canonical listing embedding text exceeds its maximum length.");
        }

        Map<String, Object> inputIdentity = new LinkedHashMap<>();
        inputIdentity.put("schemaVersion", INPUT_SCHEMA_VERSION);
        inputIdentity.put("normalizerVersion", NORMALIZER_VERSION);
        inputIdentity.put("redactorVersion", REDACTOR_VERSION);
        inputIdentity.put("language", LANGUAGE);
        inputIdentity.put("title", redact(title));
        inputIdentity.put("categoryName", redact(categoryName));
        inputIdentity.put("categorySlug", redact(categorySlug));
        inputIdentity.put("description", redact(description));

        Map<String, Object> documentIdentity = new LinkedHashMap<>();
        documentIdentity.put("schemaVersion", DOCUMENT_SCHEMA_VERSION);
        documentIdentity.put("listingId", listing.id());
        documentIdentity.put("listingVersion", listingVersion);
        documentIdentity.put("sellerType", bounded("Seller type", listing.sellerType(), 32));
        documentIdentity.put("categoryId", bounded("Category ID", listing.categoryId(), 26));
        documentIdentity.put("categorySlug", categorySlug);
        documentIdentity.put("categoryName", categoryName);
        documentIdentity.put("title", title);
        documentIdentity.put("description", description);
        documentIdentity.put("condition", bounded("Condition", listing.condition(), 32));
        documentIdentity.put("priceAmount", decimal(listing.priceAmount()));
        documentIdentity.put("currency", bounded("Currency", listing.currency(), 3));
        documentIdentity.put("publicCity", normalizedOptional(
                "Public city", listing.publicCity(), MAX_PUBLIC_LOCATION_LENGTH));
        documentIdentity.put("publicRegion", normalizedOptional(
                "Public region", listing.publicRegion(), MAX_PUBLIC_LOCATION_LENGTH));
        documentIdentity.put("publishedAt", instant(listing.publishedAt()));
        documentIdentity.put("inventoryAvailable", listing.quantity() > 0);
        documentIdentity.put("primaryImageUrl", primaryImageUrl(publicImages));

        return new ListingDiscoveryEmbeddingSource(
                DOCUMENT_SCHEMA_VERSION,
                hash(documentIdentity),
                INPUT_SCHEMA_VERSION,
                hash(inputIdentity),
                NORMALIZER_VERSION,
                REDACTOR_VERSION,
                LANGUAGE,
                embeddingText);
    }

    private String normalizedField(String field, String value, int maximumLength) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required for discovery embedding.");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its public listing limit.");
        }
        return normalized;
    }

    private String normalizedOptional(String field, String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its public listing limit.");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String canonical = Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder(canonical.length());
        canonical.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                safe.append(' ');
            } else if (!removedCodePoint(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return REPEATED_WHITESPACE.matcher(safe.toString()).replaceAll(" ").trim();
    }

    private boolean removedCodePoint(int codePoint) {
        return REMOVED_BIDI_CODE_POINTS.contains(codePoint)
                || Character.getType(codePoint) == Character.CONTROL;
    }

    private String redact(String value) {
        String withoutUrls = URL.matcher(value).replaceAll("[redacted-url]");
        String withoutEmails = EMAIL.matcher(withoutUrls).replaceAll("[redacted-email]");
        Matcher matcher = PHONE_CANDIDATE.matcher(withoutEmails);
        StringBuilder result = new StringBuilder(withoutEmails.length());
        while (matcher.find()) {
            String candidate = matcher.group();
            long digits = candidate.codePoints().filter(Character::isDigit).count();
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(digits >= 7 ? "[redacted-phone]" : candidate));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String primaryImageUrl(List<PublicListingImageResponse> publicImages) {
        if (publicImages == null || publicImages.isEmpty() || publicImages.getFirst().url() == null) {
            return null;
        }
        String value = publicImages.getFirst().url().trim();
        if (value.length() > MAX_IMAGE_URL_LENGTH) {
            throw new IllegalArgumentException("Primary public image URL exceeds its discovery limit.");
        }
        URI uri = URI.create(value);
        if (!uri.isAbsolute() && !value.startsWith("/")) {
            throw new IllegalArgumentException("Primary public image URL is invalid.");
        }
        return value;
    }

    private String bounded(String field, String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid for discovery embedding.");
        }
        return value;
    }

    private String decimal(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Price is required for the public discovery document.");
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String instant(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("Published time is required for the public discovery document.");
        }
        return value.toString();
    }

    private String hash(Map<String, Object> canonical) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Listing discovery identity could not be hashed.", exception);
        }
    }
}
