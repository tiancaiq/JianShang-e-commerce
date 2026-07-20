package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

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

    public List<ListingModerationDecisionResponse> findByListingId(String listingId) {
        return jdbcTemplate.query("""
                select id, listing_id, decision, reason, reviewer_user_id, listing_version, created_at
                from listing_moderation_decisions
                where listing_id = ?
                order by created_at desc, id desc
                """,
                (rs, rowNum) -> new ListingModerationDecisionResponse(
                        rs.getString("id"),
                        rs.getString("listing_id"),
                        rs.getString("decision"),
                        rs.getString("reason"),
                        rs.getString("reviewer_user_id"),
                        rs.getLong("listing_version"),
                        rs.getTimestamp("created_at").toInstant()),
                listingId);
    }

    public Optional<ListingModerationDecisionResponse> findLatestByListingId(String listingId) {
        return findByListingId(listingId).stream().findFirst();
    }
}
