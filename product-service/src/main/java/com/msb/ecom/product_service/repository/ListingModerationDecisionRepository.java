package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import com.msb.ecom.product_service.dto.AdminTimelineEntryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingModerationDecisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ListingModerationDecisionResponse insert(ListingModerationDecisionInsert decision) {
        jdbcTemplate.update("""
                insert into listing_moderation_decisions (
                    id, listing_id, moderation_case_id, decision, reason, reviewer_user_id,
                    listing_version, previous_state, new_state, correlation_id, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                decision.listingId(),
                decision.moderationCaseId(),
                decision.decision(),
                decision.reason(),
                decision.reviewerUserId(),
                decision.listingVersion(),
                decision.previousState(),
                decision.newState(),
                decision.correlationId(),
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

    public List<AdminTimelineEntryResponse> findTimeline(String listingId, String moderationCaseId) {
        return jdbcTemplate.query("""
                select id, listing_id, moderation_case_id, decision, reason, reviewer_user_id,
                       listing_version, previous_state, new_state, correlation_id, created_at
                from listing_moderation_decisions
                where listing_id = ?
                  and moderation_case_id = ?
                order by created_at asc, id asc
                """,
                (rs, rowNum) -> {
                    String decision = rs.getString("decision");
                    long listingVersion = rs.getLong("listing_version");
                    return new AdminTimelineEntryResponse(
                            rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            timelineEventType(decision),
                            "PLATFORM_ADMIN",
                            rs.getString("reviewer_user_id"),
                            rs.getString("reviewer_user_id"),
                            "HUMAN_ADMIN",
                            "LISTING",
                            rs.getString("listing_id"),
                            rs.getString("moderation_case_id"),
                            stateOrLegacy(rs.getString("previous_state"), decision, true),
                            stateOrLegacy(rs.getString("new_state"), decision, false),
                            rs.getString("reason"),
                            rs.getString("correlation_id"),
                            Map.of("decision", decision, "listingVersion", Long.toString(listingVersion)));
                },
                listingId,
                moderationCaseId);
    }

    private static String timelineEventType(String decision) {
        return switch (decision) {
            case "ADMIN_EDIT" -> "ACTIVE_LISTING_EDITED";
            case "ADMIN_REMOVE" -> "ACTIVE_LISTING_REMOVED";
            default -> "LISTING_MODERATION_RESOLVED";
        };
    }

    private static String stateOrLegacy(String stored, String decision, boolean previous) {
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        if (previous) {
            return "ADMIN_REMOVE".equals(decision) || "ADMIN_EDIT".equals(decision)
                    ? "ACTIVE/APPROVED"
                    : "PENDING_REVIEW/PENDING";
        }
        return switch (decision) {
            case "APPROVE", "ADMIN_EDIT" -> "ACTIVE/APPROVED";
            case "REJECT" -> "REJECTED/REJECTED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED/CHANGES_REQUESTED";
            case "ADMIN_REMOVE" -> "REMOVED_BY_ADMIN/APPROVED";
            default -> null;
        };
    }
}
