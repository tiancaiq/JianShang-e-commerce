package com.msb.ecom.product_service.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CategoryGuidanceRepository {

    private final JdbcTemplate jdbcTemplate;

    // Locks the category before allocating a stream version so first publication is race-safe.
    public Optional<CategorySnapshot> lockCategory(String categoryId) {
        List<CategorySnapshot> rows = jdbcTemplate.query("""
                select id, slug, name, status
                from categories
                where id = ?
                for update
                """,
                (rs, rowNum) -> new CategorySnapshot(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("status")),
                categoryId);
        return rows.stream().findFirst();
    }

    public Optional<CategorySnapshot> findCategory(String categoryId) {
        List<CategorySnapshot> rows = jdbcTemplate.query("""
                select id, slug, name, status
                from categories
                where id = ?
                """,
                (rs, rowNum) -> new CategorySnapshot(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("status")),
                categoryId);
        return rows.stream().findFirst();
    }

    public Optional<CategoryGuidanceVersion> findLatestForUpdate(String categoryId, String language) {
        return queryOne("""
                select *
                from category_guidance_versions
                where category_id = ? and language = ?
                order by source_version desc
                limit 1
                for update
                """, categoryId, language);
    }

    public Optional<CategoryGuidanceVersion> findLatest(String categoryId, String language) {
        return queryOne("""
                select *
                from category_guidance_versions
                where category_id = ? and language = ?
                order by source_version desc
                limit 1
                """, categoryId, language);
    }

    public Optional<CategoryGuidanceVersion> findExact(
            String categoryId,
            String language,
            long sourceVersion) {
        return queryOne("""
                select *
                from category_guidance_versions
                where category_id = ? and language = ? and source_version = ?
                """, categoryId, language, sourceVersion);
    }

    public List<CategoryGuidanceVersion> findHistory(
            String categoryId,
            String language,
            Long beforeVersion,
            int limit) {
        return jdbcTemplate.query("""
                select *
                from category_guidance_versions
                where category_id = ? and language = ?
                  and (? is null or source_version < ?)
                order by source_version desc
                limit ?
                """,
                (rs, rowNum) -> version(rs),
                categoryId,
                language,
                beforeVersion,
                beforeVersion,
                limit);
    }

    // Returns the latest active row for each stream at a fixed rebuild watermark.
    public List<CategoryGuidanceVersion> findActiveAtWatermark(
            Instant watermark,
            String afterCategoryId,
            String afterLanguage,
            int limit) {
        return jdbcTemplate.query("""
                select gv.*
                from category_guidance_versions gv
                join categories c on c.id = gv.category_id and c.status = 'ACTIVE'
                join (
                    select category_id, language, max(source_version) as source_version
                    from category_guidance_versions
                    where created_at <= ?
                    group by category_id, language
                ) latest
                  on latest.category_id = gv.category_id
                 and latest.language = gv.language
                 and latest.source_version = gv.source_version
                where gv.lifecycle = 'ACTIVE'
                  and (
                    ? is null
                    or gv.category_id > ?
                    or (gv.category_id = ? and gv.language > ?)
                  )
                order by gv.category_id asc, gv.language asc
                limit ?
                """,
                (rs, rowNum) -> version(rs),
                timestamp(watermark),
                afterCategoryId,
                afterCategoryId,
                afterCategoryId,
                afterLanguage,
                limit);
    }

    public List<CategoryGuidanceVersion> findLatestActiveForCategoryForUpdate(String categoryId) {
        return jdbcTemplate.query("""
                select gv.*
                from category_guidance_versions gv
                join (
                    select language, max(source_version) as source_version
                    from category_guidance_versions
                    where category_id = ?
                    group by language
                ) latest
                  on latest.language = gv.language
                 and latest.source_version = gv.source_version
                where gv.category_id = ? and gv.lifecycle = 'ACTIVE'
                order by gv.language
                for update
                """,
                (rs, rowNum) -> version(rs),
                categoryId,
                categoryId);
    }

    public void insertActive(CategoryGuidanceVersion version) {
        jdbcTemplate.update("""
                insert into category_guidance_versions (
                    category_id, language, source_version, supersedes_version, lifecycle,
                    visibility, category_slug, category_name, title, body, content_hash,
                    effective_from, invalidated_at, actor_user_id, correlation_id, created_at
                )
                values (?, ?, ?, ?, 'ACTIVE', 'PUBLIC', ?, ?, ?, ?, ?, ?, null, ?, ?, ?)
                """,
                version.categoryId(),
                version.language(),
                version.sourceVersion(),
                version.supersedesVersion(),
                version.categorySlug(),
                version.categoryName(),
                version.title(),
                version.body(),
                version.contentHash(),
                timestamp(version.effectiveFrom()),
                version.actorUserId(),
                version.correlationId(),
                timestamp(version.createdAt()));
    }

    public void insertInvalidated(CategoryGuidanceVersion version) {
        jdbcTemplate.update("""
                insert into category_guidance_versions (
                    category_id, language, source_version, supersedes_version, lifecycle,
                    visibility, category_slug, category_name, title, body, content_hash,
                    effective_from, invalidated_at, actor_user_id, correlation_id, created_at
                )
                values (?, ?, ?, ?, 'INVALIDATED', 'PUBLIC',
                        null, null, null, null, null, null, ?, ?, ?, ?)
                """,
                version.categoryId(),
                version.language(),
                version.sourceVersion(),
                version.supersedesVersion(),
                timestamp(version.invalidatedAt()),
                version.actorUserId(),
                version.correlationId(),
                timestamp(version.createdAt()));
    }

    private Optional<CategoryGuidanceVersion> queryOne(String sql, Object... arguments) {
        List<CategoryGuidanceVersion> rows =
                jdbcTemplate.query(sql, (rs, rowNum) -> version(rs), arguments);
        return rows.stream().findFirst();
    }

    private CategoryGuidanceVersion version(ResultSet rs) throws SQLException {
        return new CategoryGuidanceVersion(
                rs.getString("category_id"),
                rs.getString("language"),
                rs.getLong("source_version"),
                nullableLong(rs, "supersedes_version"),
                rs.getString("lifecycle"),
                rs.getString("visibility"),
                rs.getString("category_slug"),
                rs.getString("category_name"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("content_hash"),
                nullableInstant(rs, "effective_from"),
                nullableInstant(rs, "invalidated_at"),
                rs.getString("actor_user_id"),
                rs.getString("correlation_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record CategorySnapshot(String id, String slug, String name, String status) {
    }
}
