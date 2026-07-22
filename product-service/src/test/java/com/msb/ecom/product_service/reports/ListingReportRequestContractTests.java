package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingReportRequestContractTests {

    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";

    private final ListingReportRequestParser parser = new ListingReportRequestParser(
            new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator());
    private final ListingReportCanonicalizer canonicalizer = new ListingReportCanonicalizer();

    @Test
    void strictSchemaNormalizesTextAndCanonicalizesMediaOrder() {
        ListingReportCreateRequest parsed = parser.parse(json("""
                {
                  "listingId":"%s",
                  "reasonCode":"FRAUD_OR_MISREPRESENTATION",
                  "statement":"  Public   description\\nlooks inconsistent.  ",
                  "listingMediaIds":["%s"]
                }
                """.formatted(LISTING_ID, MEDIA_ID)));

        ListingReportCanonicalizer.NormalizedRequest normalized = canonicalizer.normalize(parsed);

        assertThat(normalized.statement()).isEqualTo("Public description looks inconsistent.");
        assertThat(normalized.listingMediaIds()).containsExactly(MEDIA_ID);
        assertThat(normalized.requestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void unknownFieldsMalformedIdsAndDuplicateMediaAreRejected() {
        assertThatThrownBy(() -> parser.parse(json("""
                {"listingId":"%s","reasonCode":"DUPLICATE_OR_SPAM","sellerId":"secret"}
                """.formatted(LISTING_ID))))
                .isInstanceOf(ListingReportInvalidRequestException.class);

        assertThatThrownBy(() -> parser.parse(json("""
                {"listingId":"bad","reasonCode":"DUPLICATE_OR_SPAM"}
                """)))
                .isInstanceOf(ListingReportInvalidRequestException.class);

        ListingReportCreateRequest duplicateMedia = request(
                "DUPLICATE_OR_SPAM",
                null,
                List.of(MEDIA_ID, MEDIA_ID));
        assertThatThrownBy(() -> canonicalizer.normalize(duplicateMedia))
                .isInstanceOf(ListingReportInvalidRequestException.class);
    }

    @Test
    void unsafeStatementContentIsRejected() {
        for (String unsafe : List.of(
                "See https://example.test/evidence",
                "Email me at seller@example.test",
                "Call +1 (949) 555-0100",
                "Meet at 123 Main Street",
                "Coordinates 33.6405, -117.8443",
                "<b>unsafe html</b>",
                "control\u0007character")) {
            assertThatThrownBy(() -> canonicalizer.normalize(request(
                    "OTHER_POLICY_CONCERN",
                    unsafe,
                    List.of())))
                    .as(unsafe)
                    .isInstanceOf(ListingReportInvalidRequestException.class);
        }
    }

    @Test
    void bodyLimitIsMeasuredInUtf8Bytes() {
        byte[] oversized = new byte[ListingReportRequestParser.MAX_JSON_BYTES + 1];
        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOf(ListingReportPayloadTooLargeException.class);
    }

    private ListingReportCreateRequest request(String reason, String statement, List<String> mediaIds) {
        ListingReportCreateRequest request = new ListingReportCreateRequest();
        request.setListingId(LISTING_ID);
        request.setReasonCode(reason);
        request.setStatement(statement);
        request.setListingMediaIds(mediaIds);
        return request;
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
