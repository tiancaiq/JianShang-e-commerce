package com.msb.ecom.product_service.dto;

import java.util.List;

public record AgentMarketplaceSearchResponse(
        List<Candidate> data
) {

    public record Candidate(
            String listingId,
            int lexicalRank
    ) {
    }
}
