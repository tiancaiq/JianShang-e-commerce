package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.dto.CategoryAttributeOptionResponse;
import com.msb.ecom.product_service.dto.CategoryResponse;
import com.msb.ecom.product_service.dto.CategorySellerGuidanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class CategoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean activeCategoryExists(String categoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from categories
                where id = ? and status = 'ACTIVE'
                """, Integer.class, categoryId);
        return count != null && count > 0;
    }

    public List<CategoryResponse> findActiveCategories() {
        List<CategoryAttributeOptionWithAttribute> options = jdbcTemplate.query("""
                        select attribute_id, id, option_value, label, display_order, status, version
                        from category_attribute_options
                        where status = 'ACTIVE'
                        order by attribute_id, display_order, option_value
                        """,
                (rs, rowNum) -> new CategoryAttributeOptionWithAttribute(
                        rs.getString("attribute_id"),
                        new CategoryAttributeOptionResponse(
                                rs.getString("id"),
                                rs.getString("option_value"),
                                rs.getString("label"),
                                rs.getInt("display_order"),
                                rs.getString("status"),
                                rs.getLong("version"))));
        Map<String, List<CategoryAttributeOptionResponse>> optionsByAttribute = options.stream()
                .collect(Collectors.groupingBy(
                        CategoryAttributeOptionWithAttribute::attributeId,
                        Collectors.mapping(CategoryAttributeOptionWithAttribute::option, Collectors.toList())));

        List<CategoryAttributeResponseWithCategory> attributes = jdbcTemplate.query("""
                        select category_id, id, attribute_key, label, description, data_type, `required`,
                               searchable, filterable, allowed_values_json, validation_json, display_order,
                               status, version
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
                                rs.getString("description"),
                                rs.getString("data_type"),
                                rs.getBoolean("required"),
                                rs.getBoolean("searchable"),
                                rs.getBoolean("filterable"),
                                rs.getString("allowed_values_json"),
                                rs.getString("validation_json"),
                                rs.getInt("display_order"),
                                rs.getString("status"),
                                rs.getLong("version"),
                                optionsByAttribute.getOrDefault(rs.getString("id"), List.of()))));

        Map<String, List<CategoryAttributeResponse>> attributesByCategory = attributes.stream()
                .collect(Collectors.groupingBy(
                        CategoryAttributeResponseWithCategory::categoryId,
                        Collectors.mapping(CategoryAttributeResponseWithCategory::attribute, Collectors.toList())));

        List<GuidanceWithCategory> guidance = jdbcTemplate.query("""
                        select category_id, id, guidance_type, title, body, display_order, status, version
                        from category_seller_guidance
                        where status = 'ACTIVE'
                        order by category_id, display_order, id
                        """, (rs, rowNum) -> new GuidanceWithCategory(
                        rs.getString("category_id"),
                        new CategorySellerGuidanceResponse(
                                rs.getString("id"), rs.getString("guidance_type"),
                                rs.getString("title"), rs.getString("body"),
                                rs.getInt("display_order"), rs.getString("status"),
                                rs.getLong("version"))));
        Map<String, List<CategorySellerGuidanceResponse>> guidanceByCategory = guidance.stream()
                .collect(Collectors.groupingBy(
                        GuidanceWithCategory::categoryId,
                        Collectors.mapping(GuidanceWithCategory::guidance, Collectors.toList())));

        return jdbcTemplate.query("""
                        select id, slug, name, parent_id, display_order, status, seller_eligibility,
                               listing_creation_allowed, listing_submission_allowed, current_rule_version
                        from categories
                        where status = 'ACTIVE' and listing_creation_allowed = true
                        order by display_order, name
                        """,
                (rs, rowNum) -> new CategoryResponse(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("parent_id"),
                        rs.getInt("display_order"),
                        rs.getString("status"),
                        rs.getString("seller_eligibility"),
                        rs.getBoolean("listing_creation_allowed"),
                        rs.getBoolean("listing_submission_allowed"),
                        rs.getLong("current_rule_version"),
                        attributesByCategory.getOrDefault(rs.getString("id"), List.of()),
                        guidanceByCategory.getOrDefault(rs.getString("id"), List.of())));
    }

    private record CategoryAttributeResponseWithCategory(
            String categoryId,
            CategoryAttributeResponse attribute
    ) {
    }

    private record CategoryAttributeOptionWithAttribute(
            String attributeId,
            CategoryAttributeOptionResponse option
    ) { }

    private record GuidanceWithCategory(
            String categoryId,
            CategorySellerGuidanceResponse guidance
    ) { }
}
