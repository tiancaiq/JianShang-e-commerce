package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
final class ListingReportRequestParser {

    static final int MAX_JSON_BYTES = 8 * 1024;

    private final ObjectMapper strictObjectMapper;
    private final Validator validator;

    ListingReportRequestParser(ObjectMapper objectMapper, Validator validator) {
        this.strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    // Parses the exact bounded intake schema before any Product or persistence work begins.
    ListingReportCreateRequest parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new ListingReportInvalidRequestException();
        }
        if (body.length > MAX_JSON_BYTES) {
            throw new ListingReportPayloadTooLargeException();
        }
        try {
            ListingReportCreateRequest request = strictObjectMapper.readValue(body, ListingReportCreateRequest.class);
            if (!validator.validate(request).isEmpty()) {
                throw new ListingReportInvalidRequestException();
            }
            return request;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ListingReportInvalidRequestException();
        }
    }
}
