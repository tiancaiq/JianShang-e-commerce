package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.dto.CategoryResponse;
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

    private record CategoryAttributeResponseWithCategory(
            String categoryId,
            CategoryAttributeResponse attribute
    ) {
    }
}
