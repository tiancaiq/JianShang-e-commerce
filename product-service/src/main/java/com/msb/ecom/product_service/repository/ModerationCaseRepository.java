package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.AdminListingModerationCaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ModerationCaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<AdminListingModerationCaseResponse> findListingReviewCases(
            String filter,
            String currentAdminUserId,
            String searchQuery) {
        return switch (filter) {
            case "unassigned" -> queryListingCases(
                    "c.status = 'OPEN' and c.assigned_admin_user_id is null",
                    "order by c.created_at asc, c.id asc",
                    List.of(),
                    searchQuery);
            case "assigned_to_me" -> queryListingCases(
                    "c.status = 'CLAIMED' and c.assigned_admin_user_id = ?",
                    "order by c.updated_at desc, c.id asc",
                    List.of(currentAdminUserId),
                    searchQuery);
            case "resolved" -> queryListingCases(
                    "c.status = 'RESOLVED'",
                    "order by c.resolved_at desc, c.id asc",
                    List.of(),
                    searchQuery);
            default -> queryListingCases(
                    "c.status in ('OPEN', 'CLAIMED')",
                    "order by c.created_at asc, c.id asc",
                    List.of(),
                    searchQuery);
        };
    }

    private List<AdminListingModerationCaseResponse> queryListingCases(
            String predicate,
            String orderBy,
            List<Object> parameters,
            String searchQuery) {
        List<Object> queryParameters = new ArrayList<>(parameters);
        String finalPredicate = predicate;
        if (searchQuery != null && !searchQuery.isBlank()) {
            finalPredicate = finalPredicate + """

                      and (
                        lower(c.id) like ? escape '\\\\'
                        or lower(c.subject_listing_id) like ? escape '\\\\'
                        or lower(c.submitted_by_user_id) like ? escape '\\\\'
                        or lower(c.subject_individual_seller_user_id) like ? escape '\\\\'
                        or lower(c.subject_business_id) like ? escape '\\\\'
                        or lower(c.assigned_admin_user_id) like ? escape '\\\\'
                        or lower(l.id) like ? escape '\\\\'
                        or lower(l.title) like ? escape '\\\\'
                        or lower(l.sku) like ? escape '\\\\'
                      )
                    """;
            String pattern = "%" + escapedLikePattern(searchQuery.toLowerCase(Locale.ROOT)) + "%";
            for (int i = 0; i < 9; i++) {
                queryParameters.add(pattern);
            }
        }
        return jdbcTemplate.query(
                listingCaseSelect(finalPredicate + "\n" + orderBy),
                (rs, rowNum) -> listingCaseResponse(rs),
                queryParameters.toArray());
    }

    private String escapedLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public Optional<AdminListingModerationCaseResponse> findListingReviewCaseById(String caseId) {
        List<AdminListingModerationCaseResponse> matches = jdbcTemplate.query(listingCaseSelect("""
                c.id = ?
                order by c.created_at asc, c.id asc
                """),
                (rs, rowNum) -> listingCaseResponse(rs),
                caseId);
        return matches.stream().findFirst();
    }

    public int claimListingReviewCase(String caseId, long expectedVersion, String adminUserId, Instant now) {
        return jdbcTemplate.update("""
                update moderation_cases
                set status = 'CLAIMED',
                    assigned_admin_user_id = ?,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and case_type = 'LISTING_REVIEW'
                  and status = 'OPEN'
                  and assigned_admin_user_id is null
                  and version = ?
                """,
                adminUserId,
                Timestamp.from(now),
                caseId,
                expectedVersion);
    }

    public int releaseListingReviewCase(String caseId, long expectedVersion, String adminUserId, Instant now) {
        return jdbcTemplate.update("""
                update moderation_cases
                set status = 'OPEN',
                    assigned_admin_user_id = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and case_type = 'LISTING_REVIEW'
                  and status = 'CLAIMED'
                  and assigned_admin_user_id = ?
                  and version = ?
                """,
                Timestamp.from(now),
                caseId,
                adminUserId,
                expectedVersion);
    }

    public int resolveListingReviewCase(String caseId, long expectedVersion, String adminUserId, Instant now) {
        return jdbcTemplate.update("""
                update moderation_cases
                set status = 'RESOLVED',
                    resolved_at = ?,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and case_type = 'LISTING_REVIEW'
                  and status = 'CLAIMED'
                  and assigned_admin_user_id = ?
                  and version = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                caseId,
                adminUserId,
                expectedVersion);
    }

    public long countAssignedListingReviewCases(String adminUserId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from moderation_cases
                where case_type = 'LISTING_REVIEW'
                  and status = 'CLAIMED'
                  and assigned_admin_user_id = ?
                """,
                Long.class,
                adminUserId);
        return count == null ? 0 : count;
    }

    public ModerationCaseEnsureResult createOrReuseListingReviewCase(ModerationCaseInsert moderationCase) {
        Optional<String> existingCaseId = findExistingListingReviewCaseId(moderationCase.listingId());
        if (existingCaseId.isPresent()) {
            reopenListingReviewCaseForSubmission(existingCaseId.get(), moderationCase.submittedByUserId(),
                    moderationCase.now());
            return new ModerationCaseEnsureResult(existingCaseId.get(), false);
        }

        try {
            insertListingReviewCase(moderationCase);
            return new ModerationCaseEnsureResult(moderationCase.id(), true);
        } catch (DuplicateKeyException exception) {
            String existingId = findExistingListingReviewCaseId(moderationCase.listingId())
                    .orElseThrow(() -> exception);
            reopenListingReviewCaseForSubmission(existingId, moderationCase.submittedByUserId(),
                    moderationCase.now());
            return new ModerationCaseEnsureResult(existingId, false);
        }
    }

    private Optional<String> findExistingListingReviewCaseId(String listingId) {
        List<String> matches = jdbcTemplate.queryForList("""
                select id
                from moderation_cases
                where case_type = 'LISTING_REVIEW'
                  and subject_listing_id = ?
                order by
                  case when status in ('OPEN', 'CLAIMED') then 0 else 1 end,
                  created_at asc,
                  id asc
                limit 1
                """,
                String.class,
                listingId);
        return matches.stream().findFirst();
    }

    // Re-submission reuses the prior workflow identity while resetting cycle-specific resolution and claim state.
    private void reopenListingReviewCaseForSubmission(String caseId, String submittedByUserId, Instant now) {
        jdbcTemplate.update("""
                update moderation_cases
                set status = 'OPEN',
                    assigned_admin_user_id = null,
                    submitted_by_user_id = ?,
                    resolved_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and case_type = 'LISTING_REVIEW'
                """,
                submittedByUserId,
                Timestamp.from(now),
                caseId);
    }

    // Creates the listing-review workflow item after listing submission succeeds.
    private void insertListingReviewCase(ModerationCaseInsert moderationCase) {
        jdbcTemplate.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id,
                    submitted_by_user_id, status, priority, version, created_at, updated_at
                )
                values (?, 'LISTING_REVIEW', ?, ?, ?, ?, ?, 'OPEN', 'NORMAL', 0, ?, ?)
                """,
                moderationCase.id(),
                moderationCase.listingId(),
                moderationCase.sellerType().name(),
                moderationCase.individualSellerUserId(),
                moderationCase.businessId(),
                moderationCase.submittedByUserId(),
                Timestamp.from(moderationCase.now()),
                Timestamp.from(moderationCase.now()));
    }

    private String listingCaseSelect(String predicateAndOrder) {
        return """
                select c.id, c.status as case_status, c.priority, c.assigned_admin_user_id,
                       c.version, c.created_at, c.updated_at, c.resolved_at, c.submitted_by_user_id,
                       c.subject_individual_seller_user_id, c.subject_business_id,
                       l.id as listing_id, l.title, l.seller_type, l.status as listing_status,
                       l.moderation_status as listing_moderation_status, l.price_amount, l.currency,
                       l.public_city, l.public_region, l.sku, l.quantity
                from moderation_cases c
                join listings l on l.id = c.subject_listing_id
                where c.case_type = 'LISTING_REVIEW'
                  and %s
                """.formatted(predicateAndOrder);
    }

    private AdminListingModerationCaseResponse listingCaseResponse(ResultSet rs) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        String sellerType = rs.getString("seller_type");
        String sellerId = "BUSINESS".equals(sellerType)
                ? rs.getString("subject_business_id")
                : rs.getString("subject_individual_seller_user_id");
        return new AdminListingModerationCaseResponse(
                rs.getString("id"),
                rs.getString("case_status"),
                rs.getString("priority"),
                rs.getString("assigned_admin_user_id"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rs.getString("submitted_by_user_id"),
                sellerId,
                null,
                null,
                rs.getString("listing_id"),
                rs.getString("title"),
                sellerType,
                rs.getString("listing_status"),
                rs.getString("listing_moderation_status"),
                rs.getBigDecimal("price_amount"),
                rs.getString("currency"),
                rs.getString("public_city"),
                rs.getString("public_region"),
                rs.getString("sku"),
                rs.getInt("quantity"));
    }

    public record ModerationCaseEnsureResult(String id, boolean created) {
    }
}
