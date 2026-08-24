package com.msb.ecom.product_service.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.CategoryAttributeOptionResponse;
import com.msb.ecom.product_service.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.dto.CategorySellerGuidanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

@Repository
@RequiredArgsConstructor
class CatalogRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    List<CategorySummary> search(String query, CategoryStatus status, SellerEligibility eligibility) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            where.append(" and (lower(c.name) like ? or lower(c.slug) like ?)");
            String like = "%" + query.trim().toLowerCase() + "%";
            args.add(like); args.add(like);
        }
        if (status != null) { where.append(" and c.status=?"); args.add(status.name()); }
        if (eligibility != null) { where.append(" and c.seller_eligibility=?"); args.add(eligibility.name()); }
        return jdbc.query(summarySql() + where + " order by c.parent_id,c.display_order,c.name,c.id",
                this::summary, args.toArray());
    }

    Optional<CategorySummary> findSummary(String id) {
        return jdbc.query(summarySql() + " where c.id=?", this::summary, id).stream().findFirst();
    }

    Optional<CategoryRow> find(String id) {
        return queryCategory(" where id=?", id);
    }

    Optional<CategoryRow> lock(String id) {
        return queryCategory(" where id=? for update", id);
    }

    Optional<CategoryRow> findBySlug(String slug) {
        return queryCategory(" where slug=?", slug);
    }

    List<CategorySummary> children(String id) {
        return jdbc.query(summarySql() + " where c.parent_id=? order by c.display_order,c.name,c.id",
                this::summary, id);
    }

    List<CategoryPath> breadcrumb(String id) {
        return jdbc.query("""
                with recursive path as (
                  select id,parent_id,name,slug,0 depth from categories where id=?
                  union all
                  select c.id,c.parent_id,c.name,c.slug,p.depth+1
                  from categories c join path p on p.parent_id=c.id where p.depth<8
                )
                select id,name,slug from path order by depth desc
                """, (rs, n) -> new CategoryPath(rs.getString(1), rs.getString(2), rs.getString(3)), id);
    }

    boolean parentWouldCycle(String categoryId, String parentId) {
        Integer value = jdbc.queryForObject("""
                with recursive descendants as (
                  select id from categories where parent_id=?
                  union all select c.id from categories c join descendants d on c.parent_id=d.id
                ) select count(*) from descendants where id=?
                """, Integer.class, categoryId, parentId);
        return value != null && value > 0;
    }

    int depthFrom(String categoryId, String parentId) {
        Integer parentDepth = parentId == null ? 0 : jdbc.queryForObject("""
                with recursive ancestors as (
                  select id,parent_id,1 depth from categories where id=?
                  union all select c.id,c.parent_id,a.depth+1
                  from categories c join ancestors a on a.parent_id=c.id where a.depth<8
                ) select coalesce(max(depth),0) from ancestors
                """, Integer.class, parentId);
        Integer descendantDepth = jdbc.queryForObject("""
                with recursive descendants as (
                  select id,parent_id,1 depth from categories where id=?
                  union all select c.id,c.parent_id,d.depth+1
                  from categories c join descendants d on c.parent_id=d.id where d.depth<8
                ) select coalesce(max(depth),1) from descendants
                """, Integer.class, categoryId);
        return (parentDepth == null ? 0 : parentDepth) + (descendantDepth == null ? 1 : descendantDepth);
    }

    void insertCategory(CategoryRow row) {
        jdbc.update("""
                insert into categories(id,parent_id,slug,name,description,status,seller_eligibility,
                  listing_creation_allowed,listing_submission_allowed,replacement_category_id,
                  display_order,current_rule_version,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,1,0,?,?)
                """, row.id(), row.parentId(), row.slug(), row.name(), row.description(), row.status().name(),
                row.sellerEligibility().name(), row.creationAllowed(), row.submissionAllowed(),
                row.replacementCategoryId(), row.displayOrder(), Timestamp.from(row.createdAt()),
                Timestamp.from(row.updatedAt()));
    }

    int updateMetadata(String id, long version, String name, String description, int order,
                       SellerEligibility eligibility, Instant now) {
        return jdbc.update("""
                update categories set name=?,description=?,display_order=?,seller_eligibility=?,
                  version=version+1,updated_at=? where id=? and version=?
                """, name, description, order, eligibility.name(), Timestamp.from(now), id, version);
    }

    int move(String id, long version, String parentId, Instant now) {
        return jdbc.update("""
                update categories set parent_id=?,version=version+1,updated_at=? where id=? and version=?
                """, parentId, Timestamp.from(now), id, version);
    }

    int updateStatus(String id, long version, CategoryStatus status, Instant now) {
        return jdbc.update("""
                update categories set status=?,current_rule_version=current_rule_version+1,
                  version=version+1,updated_at=?
                where id=? and version=?
                """, status.name(), Timestamp.from(now), id, version);
    }

    int updatePolicy(String id, long version, SellerEligibility eligibility, boolean creation,
                     boolean submission, String replacement, Instant now) {
        return jdbc.update("""
                update categories set seller_eligibility=?,listing_creation_allowed=?,
                  listing_submission_allowed=?,replacement_category_id=?,
                  current_rule_version=current_rule_version+1,version=version+1,updated_at=?
                where id=? and version=?
                """, eligibility.name(), creation, submission, replacement,
                Timestamp.from(now), id, version);
    }

    List<CategoryAttributeResponse> attributes(String categoryId, boolean activeOnly) {
        String suffix = activeOnly ? " and a.status='ACTIVE'" : "";
        List<CategoryAttributeOptionResponseWithAttribute> optionRows = jdbc.query("""
                select o.attribute_id,o.id,o.option_value,o.label,o.display_order,o.status,o.version
                from category_attribute_options o
                join category_attribute_definitions a on a.id=o.attribute_id
                where a.category_id=? order by o.attribute_id,o.display_order,o.option_value
                """, (rs, n) -> new CategoryAttributeOptionResponseWithAttribute(
                rs.getString("attribute_id"), new CategoryAttributeOptionResponse(
                rs.getString("id"), rs.getString("option_value"), rs.getString("label"),
                rs.getInt("display_order"), rs.getString("status"), rs.getLong("version"))), categoryId);
        Map<String, List<CategoryAttributeOptionResponse>> options = new LinkedHashMap<>();
        optionRows.forEach(row -> options.computeIfAbsent(row.attributeId(), ignored -> new ArrayList<>()).add(row.option()));
        return jdbc.query("""
                select a.id,a.attribute_key,a.label,a.description,a.data_type,a.required,a.searchable,
                  a.filterable,a.allowed_values_json,a.validation_json,a.display_order,a.status,a.version
                from category_attribute_definitions a where a.category_id=?
                """ + suffix + " order by a.display_order,a.attribute_key", (rs, n) -> new CategoryAttributeResponse(
                rs.getString("id"), rs.getString("attribute_key"), rs.getString("label"),
                rs.getString("description"), rs.getString("data_type"), rs.getBoolean("required"),
                rs.getBoolean("searchable"), rs.getBoolean("filterable"), rs.getString("allowed_values_json"),
                rs.getString("validation_json"), rs.getInt("display_order"), rs.getString("status"),
                rs.getLong("version"), options.getOrDefault(rs.getString("id"), List.of())), categoryId);
    }

    Optional<AttributeRow> lockAttribute(String categoryId, String attributeId) {
        return jdbc.query("""
                select id,category_id,attribute_key,label,description,data_type,required,searchable,filterable,
                  validation_json,display_order,status,version,created_at,updated_at
                from category_attribute_definitions where id=? and category_id=? for update
                """, this::attribute, attributeId, categoryId).stream().findFirst();
    }

    void insertAttribute(AttributeRow row) {
        jdbc.update("""
                insert into category_attribute_definitions(id,category_id,attribute_key,label,description,
                  data_type,required,searchable,filterable,allowed_values_json,validation_json,
                  display_order,status,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,null,?,?,?,0,?,?)
                """, row.id(), row.categoryId(), row.key(), row.label(), row.description(), row.dataType().name(),
                row.required(), row.searchable(), row.filterable(), row.validationJson(), row.displayOrder(),
                row.status().name(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
    }

    int updateAttribute(AttributeRow row, long expectedVersion, Instant now) {
        return jdbc.update("""
                update category_attribute_definitions set label=?,description=?,required=?,searchable=?,
                  filterable=?,validation_json=?,display_order=?,version=version+1,updated_at=?
                where id=? and category_id=? and version=?
                """, row.label(), row.description(), row.required(), row.searchable(), row.filterable(),
                row.validationJson(), row.displayOrder(), Timestamp.from(now), row.id(), row.categoryId(), expectedVersion);
    }

    int updateAttributeStatus(String categoryId, String id, long version, AttributeStatus status, Instant now) {
        return jdbc.update("""
                update category_attribute_definitions set status=?,version=version+1,updated_at=?
                where id=? and category_id=? and version=?
                """, status.name(), Timestamp.from(now), id, categoryId, version);
    }

    void insertOption(String id, String attributeId, String value, String label, int order, Instant now) {
        jdbc.update("""
                insert into category_attribute_options(id,attribute_id,option_value,label,display_order,status,
                  version,created_at,updated_at) values(?,?,?,?,?,'ACTIVE',0,?,?)
                """, id, attributeId, value, label, order, Timestamp.from(now), Timestamp.from(now));
    }

    Optional<OptionRow> lockOption(String attributeId, String optionId) {
        return jdbc.query("""
                select id,attribute_id,option_value,label,display_order,status,version
                from category_attribute_options where id=? and attribute_id=? for update
                """, (rs,n)->new OptionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getInt(5),rs.getString(6),rs.getLong(7)), optionId, attributeId).stream().findFirst();
    }

    int updateOptionStatus(String attributeId, String id, long version, String status, Instant now) {
        return jdbc.update("""
                update category_attribute_options set status=?,version=version+1,updated_at=?
                where id=? and attribute_id=? and version=?
                """, status, Timestamp.from(now), id, attributeId, version);
    }

    List<CategorySellerGuidanceResponse> guidance(String categoryId, boolean activeOnly) {
        return jdbc.query("""
                select id,guidance_type,title,body,display_order,status,version from category_seller_guidance
                where category_id=?
                """ + (activeOnly ? " and status='ACTIVE'" : "") + " order by display_order,id",
                (rs,n)->new CategorySellerGuidanceResponse(rs.getString(1),rs.getString(2),rs.getString(3),
                        rs.getString(4),rs.getInt(5),rs.getString(6),rs.getLong(7)), categoryId);
    }

    void insertGuidance(String id, String categoryId, GuidanceType type, String title, String body,
                        int order, Instant now) {
        jdbc.update("""
                insert into category_seller_guidance(id,category_id,guidance_type,title,body,display_order,
                  status,version,created_at,updated_at) values(?,?,?,?,?,?,'ACTIVE',0,?,?)
                """, id, categoryId, type.name(), title, body, order, Timestamp.from(now), Timestamp.from(now));
    }

    Optional<GuidanceRow> lockGuidance(String categoryId, String id) {
        return jdbc.query("""
                select id,category_id,guidance_type,title,body,display_order,status,version
                from category_seller_guidance where id=? and category_id=? for update
                """, (rs,n)->new GuidanceRow(rs.getString(1),rs.getString(2),GuidanceType.valueOf(rs.getString(3)),
                rs.getString(4),rs.getString(5),rs.getInt(6),rs.getString(7),rs.getLong(8)), id, categoryId)
                .stream().findFirst();
    }

    int updateGuidance(GuidanceRow row, long version, Instant now) {
        return jdbc.update("""
                update category_seller_guidance set guidance_type=?,title=?,body=?,display_order=?,
                  version=version+1,updated_at=? where id=? and category_id=? and version=?
                """, row.type().name(), row.title(), row.body(), row.displayOrder(), Timestamp.from(now),
                row.id(), row.categoryId(), version);
    }

    int updateGuidanceStatus(String categoryId, String id, long version, String status, Instant now) {
        return jdbc.update("""
                update category_seller_guidance set status=?,version=version+1,updated_at=?
                where id=? and category_id=? and version=?
                """, status, Timestamp.from(now), id, categoryId, version);
    }

    long publishRule(String categoryId, String ruleId, String json, String hash, String reason,
                     String actorId, Instant now) {
        Long version = jdbc.queryForObject("select current_rule_version from categories where id=? for update",
                Long.class, categoryId);
        long next = version == null ? 1 : version;
        jdbc.update("""
                insert into category_rule_versions(id,category_id,version_number,configuration_json,
                  configuration_hash,reason,created_by_admin_id,created_at) values(?,?,?,?,?,?,?,?)
                """, ruleId, categoryId, next, json, hash, reason, actorId, Timestamp.from(now));
        return next;
    }

    void bumpRule(String categoryId, Instant now) {
        jdbc.update("""
                update categories set current_rule_version=current_rule_version+1,version=version+1,updated_at=?
                where id=?
                """, Timestamp.from(now), categoryId);
    }

    List<RuleVersion> rules(String categoryId) {
        return jdbc.query("""
                select id,version_number,reason,created_by_admin_id,created_at from category_rule_versions
                where category_id=? order by version_number desc limit 50
                """, (rs,n)->new RuleVersion(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                rs.getTimestamp(5).toInstant()), categoryId);
    }

    void event(String id, String categoryId, String eventType, String actorId, String targetType,
               String targetId, String before, String after, String reason, String correlationId,
               String requestId, String metadata, Instant now) {
        jdbc.update("""
                insert into catalog_events(id,category_id,event_type,actor_type,actor_id,target_type,target_id,
                  previous_state_json,new_state_json,reason,correlation_id,request_id,safe_metadata_json,occurred_at)
                values(?,?,?,'HUMAN_ADMIN',?,?,?,?,?,?,?,?,?,?)
                """, id, categoryId, eventType, actorId, targetType, targetId, before, after, reason,
                correlationId, requestId, metadata, Timestamp.from(now));
    }

    List<CatalogEvent> events(String categoryId) {
        return jdbc.query("""
                select id,occurred_at,event_type,actor_id,target_type,target_id,previous_state_json,
                  new_state_json,reason,correlation_id,request_id,safe_metadata_json
                from catalog_events where category_id=? order by occurred_at desc,id desc limit 100
                """, (rs,n)->new CatalogEvent(rs.getString(1),rs.getTimestamp(2).toInstant(),rs.getString(3),
                rs.getString(4),null,rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),
                rs.getString(9),rs.getString(10),rs.getString(11),stringMap(rs.getString(12))), categoryId);
    }

    Optional<IdempotencyRow> idempotency(String actor, String operation, String key) {
        return jdbc.query("""
                select request_hash,target_id from catalog_command_idempotency
                where actor_user_id=? and operation=? and idempotency_key=?
                """, (rs,n)->new IdempotencyRow(rs.getString(1),rs.getString(2)), actor,operation,key)
                .stream().findFirst();
    }

    void insertIdempotency(String id, String actor, String operation, String key, String hash,
                           String target, Instant now) {
        jdbc.update("""
                insert into catalog_command_idempotency(id,actor_user_id,operation,idempotency_key,
                  request_hash,target_id,created_at) values(?,?,?,?,?,?,?)
                """, id,actor,operation,key,hash,target,Timestamp.from(now));
    }

    long missingRequired(String categoryId, String attributeId) {
        Long count = jdbc.queryForObject("""
                select count(*) from listings l left join listing_attributes a
                  on a.listing_id=l.id and a.attribute_definition_id=?
                where l.category_id=? and l.status in ('DRAFT','PENDING_REVIEW','ACTIVE') and a.listing_id is null
                """, Long.class, attributeId, categoryId);
        return count == null ? 0 : count;
    }

    long listingCount(String categoryId) {
        Long count = jdbc.queryForObject("select count(*) from listings where category_id=?", Long.class, categoryId);
        return count == null ? 0 : count;
    }

    long countByStatus(String status) {
        Long value = jdbc.queryForObject("select count(*) from categories where status=?", Long.class, status);
        return value == null ? 0 : value;
    }
    long activeAttributeCount() {
        Long value=jdbc.queryForObject("select count(*) from category_attribute_definitions where status='ACTIVE'",Long.class);
        return value==null?0:value;
    }
    long activeGuidanceCount() {
        Long value=jdbc.queryForObject("select count(*) from category_seller_guidance where status='ACTIVE'",Long.class);
        return value==null?0:value;
    }

    private String summarySql() {
        return """
                select c.id,c.parent_id,c.name,c.slug,c.description,c.status,c.display_order,
                  c.seller_eligibility,c.listing_creation_allowed,c.listing_submission_allowed,
                  c.replacement_category_id,c.current_rule_version,c.version,
                  coalesce(x.active_count,0) active_count,coalesce(x.draft_count,0) draft_count,
                  coalesce(x.pending_count,0) pending_count,coalesce(x.historical_count,0) historical_count,
                  coalesce(ch.child_count,0) child_count
                from categories c
                left join (select category_id,
                  sum(status='ACTIVE') active_count,sum(status in ('DRAFT','CHANGES_REQUESTED')) draft_count,
                  sum(status='PENDING_REVIEW') pending_count,
                  sum(status in ('SOLD','CLOSED','REJECTED')) historical_count
                  from listings group by category_id) x on x.category_id=c.id
                left join (select parent_id,count(*) child_count from categories where parent_id is not null
                  group by parent_id) ch on ch.parent_id=c.id
                """;
    }

    private CategorySummary summary(ResultSet rs, int row) throws SQLException {
        return new CategorySummary(rs.getString("id"),rs.getString("parent_id"),rs.getString("name"),
                rs.getString("slug"),rs.getString("description"),CategoryStatus.valueOf(rs.getString("status")),
                rs.getInt("display_order"),SellerEligibility.valueOf(rs.getString("seller_eligibility")),
                rs.getBoolean("listing_creation_allowed"),rs.getBoolean("listing_submission_allowed"),
                rs.getString("replacement_category_id"),rs.getLong("current_rule_version"),rs.getLong("version"),
                new CategoryCounts(rs.getLong("active_count"),rs.getLong("draft_count"),
                        rs.getLong("pending_count"),rs.getLong("historical_count"),rs.getLong("child_count")));
    }

    private Optional<CategoryRow> queryCategory(String suffix, Object... args) {
        return jdbc.query("""
                select id,parent_id,slug,name,description,status,seller_eligibility,listing_creation_allowed,
                  listing_submission_allowed,replacement_category_id,display_order,current_rule_version,
                  version,created_at,updated_at from categories
                """ + suffix, (rs,n)->new CategoryRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),CategoryStatus.valueOf(rs.getString(6)),SellerEligibility.valueOf(rs.getString(7)),
                rs.getBoolean(8),rs.getBoolean(9),rs.getString(10),rs.getInt(11),rs.getLong(12),rs.getLong(13),
                rs.getTimestamp(14).toInstant(),rs.getTimestamp(15).toInstant()), args).stream().findFirst();
    }

    private AttributeRow attribute(ResultSet rs, int n) throws SQLException {
        return new AttributeRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                AttributeType.valueOf(rs.getString(6)),rs.getBoolean(7),rs.getBoolean(8),rs.getBoolean(9),
                rs.getString(10),rs.getInt(11),AttributeStatus.valueOf(rs.getString(12)),rs.getLong(13),
                rs.getTimestamp(14).toInstant(),rs.getTimestamp(15).toInstant());
    }

    private Map<String,String> stringMap(String json) {
        if (json == null) return Map.of();
        try { return objectMapper.readValue(json, STRING_MAP); }
        catch (Exception ignored) { return Map.of(); }
    }

    record CategoryRow(String id,String parentId,String slug,String name,String description,
                       CategoryStatus status,SellerEligibility sellerEligibility,boolean creationAllowed,
                       boolean submissionAllowed,String replacementCategoryId,int displayOrder,
                       long ruleVersion,long version,Instant createdAt,Instant updatedAt) { }
    record AttributeRow(String id,String categoryId,String key,String label,String description,
                        AttributeType dataType,boolean required,boolean searchable,boolean filterable,
                        String validationJson,int displayOrder,AttributeStatus status,long version,
                        Instant createdAt,Instant updatedAt) { }
    record OptionRow(String id,String attributeId,String value,String label,int displayOrder,String status,long version) { }
    record GuidanceRow(String id,String categoryId,GuidanceType type,String title,String body,
                       int displayOrder,String status,long version) { }
    record IdempotencyRow(String requestHash,String targetId) { }
    private record CategoryAttributeOptionResponseWithAttribute(String attributeId, CategoryAttributeOptionResponse option) { }
}
