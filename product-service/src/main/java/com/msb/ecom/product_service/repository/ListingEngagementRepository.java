package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.ListingEngagementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ListingEngagementRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean recordVisit(String listingId, String userId, Instant now) {
        int inserted = jdbcTemplate.update("""
                insert ignore into listing_visits (listing_id, user_id, created_at)
                values (?, ?, ?)
                """,
                listingId,
                userId,
                Timestamp.from(now));
        if (inserted > 0) {
            incrementVisitCount(listingId, now);
            return true;
        }
        return false;
    }

    public boolean activateLike(String listingId, String userId, Instant now) {
        int inserted = jdbcTemplate.update("""
                insert ignore into listing_likes (listing_id, user_id, active, created_at, updated_at)
                values (?, ?, true, ?, ?)
                """,
                listingId,
                userId,
                Timestamp.from(now),
                Timestamp.from(now));
        if (inserted > 0) {
            incrementLikeCount(listingId, now);
            return true;
        }
        int reactivated = jdbcTemplate.update("""
                update listing_likes
                set active = true,
                    updated_at = ?
                where listing_id = ?
                  and user_id = ?
                  and active = false
                """,
                Timestamp.from(now),
                listingId,
                userId);
        if (reactivated > 0) {
            incrementLikeCount(listingId, now);
            return true;
        }
        return false;
    }

    public boolean deactivateLike(String listingId, String userId, Instant now) {
        int changed = jdbcTemplate.update("""
                update listing_likes
                set active = false,
                    updated_at = ?
                where listing_id = ?
                  and user_id = ?
                  and active = true
                """,
                Timestamp.from(now),
                listingId,
                userId);
        if (changed > 0) {
            decrementLikeCount(listingId, now);
            return true;
        }
        return false;
    }

    public ListingEngagementResponse findEngagement(String listingId, String userId) {
        List<ListingEngagementResponse> matches = jdbcTemplate.query("""
                select l.id as listing_id,
                       coalesce(s.visit_count, 0) as visit_count,
                       coalesce(s.like_count, 0) as like_count,
                       exists(
                           select 1
                           from listing_visits v
                           where v.listing_id = l.id
                             and v.user_id = ?
                       ) as visited_by_me,
                       exists(
                           select 1
                           from listing_likes lk
                           where lk.listing_id = l.id
                             and lk.user_id = ?
                             and lk.active = true
                       ) as liked_by_me
                from listings l
                left join listing_engagement_stats s on s.listing_id = l.id
                where l.id = ?
                """,
                (rs, rowNum) -> new ListingEngagementResponse(
                        rs.getString("listing_id"),
                        rs.getLong("visit_count"),
                        rs.getLong("like_count"),
                        rs.getBoolean("visited_by_me"),
                        rs.getBoolean("liked_by_me")),
                userId,
                userId,
                listingId);
        return matches.stream()
                .findFirst()
                .orElse(new ListingEngagementResponse(listingId, 0, 0, false, false));
    }

    private void incrementVisitCount(String listingId, Instant now) {
        jdbcTemplate.update("""
                insert into listing_engagement_stats (listing_id, visit_count, like_count, updated_at)
                values (?, 1, 0, ?)
                on duplicate key update
                    visit_count = visit_count + 1,
                    updated_at = values(updated_at)
                """,
                listingId,
                Timestamp.from(now));
    }

    private void incrementLikeCount(String listingId, Instant now) {
        jdbcTemplate.update("""
                insert into listing_engagement_stats (listing_id, visit_count, like_count, updated_at)
                values (?, 0, 1, ?)
                on duplicate key update
                    like_count = like_count + 1,
                    updated_at = values(updated_at)
                """,
                listingId,
                Timestamp.from(now));
    }

    private void decrementLikeCount(String listingId, Instant now) {
        jdbcTemplate.update("""
                update listing_engagement_stats
                set like_count = greatest(like_count - 1, 0),
                    updated_at = ?
                where listing_id = ?
                """,
                Timestamp.from(now),
                listingId);
    }
}
