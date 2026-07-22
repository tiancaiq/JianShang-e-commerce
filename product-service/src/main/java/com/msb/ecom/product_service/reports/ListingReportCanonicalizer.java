package com.msb.ecom.product_service.reports;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
final class ListingReportCanonicalizer {

    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://|www\\.)");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[ .()_-]*){7,15}(?!\\d)");
    private static final Pattern COORDINATE = Pattern.compile(
            "(?<!\\d)-?\\d{1,3}\\.\\d{4,}\\s*[,/]\\s*-?\\d{1,3}\\.\\d{4,}(?!\\d)");
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "(?i)\\b\\d{1,6}\\s+[\\p{L}0-9 .'-]{1,80}\\s+(?:street|st|avenue|ave|road|rd|drive|dr|lane|ln|boulevard|blvd|court|ct)\\b");

    // Normalizes and validates the complete client-owned request before its durable hash is calculated.
    NormalizedRequest normalize(ListingReportCreateRequest request) {
        String listingId = requiredUlid(request.listingId());
        ListingReportReason reason = ListingReportReason.parse(request.reasonCode());
        String statement = normalizeStatement(request.statement());
        List<String> mediaIds = request.listingMediaIds() == null
                ? List.of()
                : new ArrayList<>(request.listingMediaIds());
        if (mediaIds.size() > 4 || new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new ListingReportInvalidRequestException();
        }
        mediaIds.replaceAll(this::requiredUlid);
        mediaIds.sort(Comparator.naturalOrder());

        String hash = sha256(canonical(List.of(
                "listing-report-request-v1",
                listingId,
                reason.name(),
                statement == null ? "" : statement,
                String.join(",", mediaIds))));
        return new NormalizedRequest(listingId, reason, statement, List.copyOf(mediaIds), hash);
    }

    String mediaSnapshotHash(ListingReportRepository.PublicMedia media) {
        return sha256(canonical(List.of(
                "listing-report-media-snapshot-v1",
                media.listingImageId(),
                media.mediaObjectId(),
                Integer.toString(media.displayOrder()),
                media.contentType().toLowerCase(Locale.ROOT),
                Long.toString(media.sizeBytes()),
                media.sourceChecksumSha256() == null ? "" : media.sourceChecksumSha256().toLowerCase(Locale.ROOT))));
    }

    String subjectHash(ListingReportRepository.PublicSubject subject, List<HashedMedia> media) {
        List<String> values = new ArrayList<>(List.of(
                "listing-report-subject-snapshot-v1",
                subject.listingId(),
                Long.toString(subject.listingVersion()),
                subject.sellerType(),
                subject.categoryId(),
                subject.title(),
                subject.description(),
                subject.conditionCode(),
                nullToEmpty(subject.conditionNotes()),
                normalizedDecimal(subject.priceAmount()),
                subject.currency(),
                Boolean.toString(subject.negotiable()),
                Integer.toString(subject.quantity()),
                nullToEmpty(subject.publicCity()),
                nullToEmpty(subject.publicRegion()),
                nullToEmpty(subject.publicationSource()),
                subject.publishedAt().toString()));
        media.stream()
                .sorted(Comparator.comparingInt(item -> item.media().displayOrder()))
                .forEach(item -> values.add(item.hash()));
        return sha256(canonical(values));
    }

    String statementHash(String statement) {
        return sha256(canonical(List.of("listing-report-statement-v1", statement)));
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String requiredUlid(String value) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new ListingReportInvalidRequestException();
        }
        return value;
    }

    private String normalizeStatement(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isISOControl(character) && !Character.isWhitespace(character)) {
                throw new ListingReportInvalidRequestException();
            }
        }
        normalized = WHITESPACE.matcher(normalized.trim()).replaceAll(" ");
        if (normalized.isEmpty() || normalized.length() > 2000
                || normalized.indexOf('<') >= 0 || normalized.indexOf('>') >= 0
                || URL.matcher(normalized).find()
                || EMAIL.matcher(normalized).find()
                || PHONE.matcher(normalized).find()
                || COORDINATE.matcher(normalized).find()
                || STREET_ADDRESS.matcher(normalized).find()) {
            throw new ListingReportInvalidRequestException();
        }
        return normalized;
    }

    private String canonical(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(value.length()).append(':').append(value).append('|');
        }
        return builder.toString();
    }

    private String normalizedDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record NormalizedRequest(
            String listingId,
            ListingReportReason reason,
            String statement,
            List<String> listingMediaIds,
            String requestHash
    ) {
    }

    record HashedMedia(ListingReportRepository.PublicMedia media, String hash) {
    }
}
