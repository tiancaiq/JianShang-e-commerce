package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
@RequiredArgsConstructor
public class ListingModerationDecisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ListingModerationDecisionResponse insert(ListingModerationDecisionInsert decision) {
        jdbcTemplate.update("""
                insert into listing_moderation_decisions (
                    id, listing_id, decision, reason, reviewer_user_id, listing_version, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                decision.listingId(),
                decision.decision(),
                decision.reason(),
                decision.reviewerUserId(),
                decision.listingVersion(),
                Timestamp.from(decision.now()));

        return new ListingModerationDecisionResponse(
                decision.id(),
                decision.listingId(),
                decision.decision(),
                decision.reason(),
                decision.reviewerUserId(),
                decision.listingVersion(),
                decision.now());
    }
}
