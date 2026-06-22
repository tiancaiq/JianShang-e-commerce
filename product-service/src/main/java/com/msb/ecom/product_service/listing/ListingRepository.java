package com.msb.ecom.product_service.listing;

import com.msb.ecom.product_service.listing.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.listing.dto.CategoryResponse;
import com.msb.ecom.product_service.listing.dto.ListingDraftResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ListingRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean activeCategoryExists(String categoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from categories
                where id = ? and status = 'ACTIVE'
                """, Integer.class, categoryId);
        return count != null && count > 0;
    }

    public List<CategoryResponse> findActiveCategories() {
        List<CategoryAttributeResponseWithCategory> attributes = jdbcTemplate.query("""
                        select category_id, id, attribute_key, label, data_type, `required`,
                               allowed_values_json, validation_json, display_order
                        from category_attribute_definitions
                        where status = 'ACTIVE'
                        order by category_id, display_order, attribute_key
                        """,
                (rs, rowNum) -> new CategoryAttributeResponseWithCategory(
                        rs.getString("category_id"),
                        new CategoryAttributeResponse(
                                rs.getString("id"),
                                rs.getString("attribute_key"),
                                rs.getString("label"),
                                rs.getString("data_type"),
                                rs.getBoolean("required"),
                                rs.getString("allowed_values_json"),
                                rs.getString("validation_json"),
                                rs.getInt("display_order"))));

        Map<String, List<CategoryAttributeResponse>> attributesByCategory = attributes.stream()
                .collect(Collectors.groupingBy(
                        CategoryAttributeResponseWithCategory::categoryId,
                        Collectors.mapping(CategoryAttributeResponseWithCategory::attribute, Collectors.toList())));

        return jdbcTemplate.query("""
                        select id, slug, name, parent_id, display_order
                        from categories
                        where status = 'ACTIVE'
                        order by display_order, name
                        """,
                (rs, rowNum) -> new CategoryResponse(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("parent_id"),
                        rs.getInt("display_order"),
                        attributesByCategory.getOrDefault(rs.getString("id"), List.of())));
    }

    public ListingDraftResponse insertDraft(ListingDraftInsert draft) {
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city, public_region,
                    status, moderation_status, version, created_at, updated_at
                )
                values (?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'DRAFT', 'NOT_SUBMITTED', 0, ?, ?)
                """,
                draft.id(),
                draft.sellerType().name(),
                draft.individualSellerUserId(),
                draft.businessId(),
                draft.categoryId(),
                draft.title(),
                draft.description(),
                draft.condition().name(),
                draft.conditionNotes(),
                draft.priceAmount(),
                draft.currency(),
                draft.negotiable(),
                draft.sku(),
                draft.quantity(),
                draft.publicCity(),
                draft.publicRegion(),
                Timestamp.from(draft.now()),
                Timestamp.from(draft.now()));

        return findById(draft.id());
    }

    private ListingDraftResponse findById(String listingId) {
        return jdbcTemplate.queryForObject("""
                select id, seller_type, individual_seller_user_id, business_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, version, created_at, updated_at
                from listings
                where id = ?
                """,
                (rs, rowNum) -> new ListingDraftResponse(
                        rs.getString("id"),
                        rs.getString("seller_type"),
                        rs.getString("individual_seller_user_id"),
                        rs.getString("business_id"),
                        rs.getString("category_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("condition_code"),
                        rs.getString("condition_notes"),
                        rs.getBigDecimal("price_amount"),
                        rs.getString("currency"),
                        rs.getBoolean("negotiable"),
                        rs.getString("sku"),
                        rs.getInt("quantity"),
                        rs.getString("public_city"),
                        rs.getString("public_region"),
                        rs.getString("status"),
                        rs.getString("moderation_status"),
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                listingId);
    }

    private record CategoryAttributeResponseWithCategory(
            String categoryId,
            CategoryAttributeResponse attribute
    ) {
    }
}
