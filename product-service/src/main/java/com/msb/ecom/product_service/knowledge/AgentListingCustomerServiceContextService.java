package com.msb.ecom.product_service.knowledge;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class AgentListingCustomerServiceContextService {

    private static final String TRANSACTION_NOTICE =
            "Payment and delivery are arranged directly by participants. "
                    + "The platform does not verify or protect off-platform payment.";

    private final ListingKnowledgeRepository knowledgeRepository;
    private final ListingMediaRepository mediaRepository;
    private final String internalServiceToken;

    public AgentListingCustomerServiceContextService(
            ListingKnowledgeRepository knowledgeRepository,
            ListingMediaRepository mediaRepository,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        this.knowledgeRepository = knowledgeRepository;
        this.mediaRepository = mediaRepository;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Supplies the current public listing snapshot used to authorize an agent session.
    public AgentListingCustomerServiceContext get(String suppliedToken, String listingId) {
        requireInternalToken(suppliedToken);
        String normalizedId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        ListingKnowledgeVersion version = knowledgeRepository.findLatest(normalizedId)
                .filter(item -> "ACTIVE".equals(item.lifecycle()))
                .filter(item -> "INDIVIDUAL".equals(item.sellerType()))
                .filter(item -> "PUBLIC".equals(item.visibility()))
                .orElseThrow(ListingKnowledgeSourceNotFoundException::new);
        String thumbnail = mediaRepository.findPublicImagesByListingId(normalizedId).stream()
                .findFirst()
                .map(image -> image.url())
                .orElse(null);
        return new AgentListingCustomerServiceContext(
                normalizedId,
                Long.toString(version.sourceVersion()),
                true,
                version.sellerType(),
                version.title(),
                thumbnail,
                TRANSACTION_NOTICE);
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Agent source authentication is required.");
        }
    }
}
