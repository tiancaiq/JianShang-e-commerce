package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.dto.AdminBusinessListingSummaryContracts.Envelope;
import com.msb.ecom.product_service.dto.AdminBusinessListingSummaryContracts.Request;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class AdminBusinessListingSummaryService {
    private static final int MAX_BUSINESSES = 100;

    private final ListingDraftRepository repository;
    private final String internalServiceToken;

    public AdminBusinessListingSummaryService(
            ListingDraftRepository repository,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.repository = repository;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    public Envelope summaries(String suppliedToken, Request request) {
        requireInternalToken(suppliedToken);
        if (request == null || request.businessIds() == null || request.businessIds().isEmpty()) {
            throw new IllegalArgumentException("At least one business ID is required.");
        }
        if (request.businessIds().size() > MAX_BUSINESSES) {
            throw new IllegalArgumentException("At most 100 business IDs can be summarized.");
        }
        Set<String> ids = new LinkedHashSet<>();
        request.businessIds().forEach(id -> ids.add(FixedLengthIds.requireTrimmed("Business ID", id, 26)));
        return new Envelope(repository.summarizeBusinesses(ids));
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Internal commerce service authentication is required.");
        }
    }
}
