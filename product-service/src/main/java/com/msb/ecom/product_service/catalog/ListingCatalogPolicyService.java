package com.msb.ecom.product_service.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingCatalogValuesResponse;
import com.msb.ecom.product_service.model.ListingSellerType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ListingCatalogPolicyService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // Validates seller-entered fields against one immutable category rule version before draft persistence.
    public ValidatedCatalogValues validateDraft(
            String categoryId,
            ListingSellerType sellerType,
            Long requestedRuleVersion,
            Map<String, Object> requestedAttributes) {
        CategoryPolicy category = category(categoryId);
        requireUsable(category, sellerType, false);
        if (requestedRuleVersion != null && requestedRuleVersion != category.ruleVersion()) {
            throw ruleChanged(category);
        }
        return new ValidatedCatalogValues(
                category.ruleVersion(),
                normalizeAttributes(categoryId, requestedAttributes, false));
    }

    // Rechecks the captured rule and all required fields at the irreversible publish/submission boundary.
    public void validateSubmission(String listingId, ListingSellerType sellerType) {
        ListingPolicy listing = jdbc.query("""
                select id,category_id,category_rule_version from listings where id=?
                """, (rs, row) -> new ListingPolicy(
                rs.getString("id"), rs.getString("category_id"), rs.getLong("category_rule_version")), listingId)
                .stream().findFirst()
                .orElseThrow(() -> new CatalogException(HttpStatus.NOT_FOUND,
                        "LISTING_NOT_FOUND", "Listing was not found."));
        CategoryPolicy category = category(listing.categoryId());
        requireUsable(category, sellerType, true);
        if (listing.ruleVersion() != category.ruleVersion()) {
            throw ruleChanged(category);
        }
        Map<String, Object> saved = readAttributes(listingId);
        normalizeAttributes(listing.categoryId(), saved, true);
    }

    public void replaceAttributes(String listingId, ValidatedCatalogValues values, Instant now) {
        jdbc.update("delete from listing_attributes where listing_id=?", listingId);
        for (NormalizedAttribute value : values.attributes()) {
            jdbc.update("""
                    insert into listing_attributes(listing_id,attribute_definition_id,string_value,
                      number_value,boolean_value,date_value,created_at,updated_at)
                    values(?,?,?,?,?,null,?,?)
                    """, listingId, value.definitionId(), value.stringValue(), value.numberValue(),
                    value.booleanValue(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    public ListingCatalogValuesResponse values(String listingId) {
        return jdbc.query("""
                select id,category_id,category_rule_version from listings where id=?
                """, (rs, row) -> new ListingCatalogValuesResponse(
                rs.getString("id"), rs.getString("category_id"), rs.getLong("category_rule_version"),
                readAttributes(listingId)), listingId).stream().findFirst()
                .orElseThrow(() -> new CatalogException(HttpStatus.NOT_FOUND,
                        "LISTING_NOT_FOUND", "Listing was not found."));
    }

    private CategoryPolicy category(String categoryId) {
        return jdbc.query("""
                select id,status,seller_eligibility,listing_creation_allowed,listing_submission_allowed,
                  current_rule_version from categories where id=?
                """, (rs, row) -> new CategoryPolicy(
                rs.getString("id"), rs.getString("status"), rs.getString("seller_eligibility"),
                rs.getBoolean("listing_creation_allowed"), rs.getBoolean("listing_submission_allowed"),
                rs.getLong("current_rule_version")), categoryId).stream().findFirst()
                .orElseThrow(() -> new CatalogException(HttpStatus.NOT_FOUND,
                        "CATEGORY_NOT_FOUND", "Category was not found."));
    }

    private void requireUsable(CategoryPolicy category, ListingSellerType sellerType, boolean submission) {
        if (!"ACTIVE".equals(category.status())
                || (submission ? !category.submissionAllowed() : !category.creationAllowed())) {
            throw new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                    submission ? "CATEGORY_SUBMISSION_DISABLED" : "CATEGORY_CREATION_DISABLED",
                    submission
                            ? "This category is not accepting listing submissions."
                            : "This category is not accepting new or updated listing drafts.");
        }
        boolean eligible = "BOTH".equals(category.eligibility())
                || sellerType.name().equals(category.eligibility());
        if (!eligible) {
            throw new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CATEGORY_SELLER_INELIGIBLE", "This seller type is not eligible for the category.");
        }
    }

    private CatalogException ruleChanged(CategoryPolicy category) {
        return new CatalogException(HttpStatus.CONFLICT, "CATEGORY_RULE_CHANGED",
                "Category requirements changed. Reload the category fields and review the listing before saving.");
    }

    private List<NormalizedAttribute> normalizeAttributes(
            String categoryId, Map<String, Object> requested, boolean requireRequired) {
        Map<String, Object> supplied = requested == null ? Map.of() : requested;
        Map<String, AttributePolicy> definitions = new LinkedHashMap<>();
        jdbc.query("""
                select id,attribute_key,data_type,required,validation_json
                from category_attribute_definitions
                where category_id=? and status='ACTIVE'
                order by display_order,attribute_key
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> definitions.put(
                rs.getString("attribute_key"), new AttributePolicy(
                        rs.getString("id"), rs.getString("attribute_key"), rs.getString("data_type"),
                        rs.getBoolean("required"), parseMap(rs.getString("validation_json")))), categoryId);

        for (String key : supplied.keySet()) {
            if (!definitions.containsKey(key)) {
                throw new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "CATEGORY_ATTRIBUTE_UNKNOWN", "Attribute '" + key + "' is not active for this category.");
            }
        }

        List<NormalizedAttribute> normalized = new ArrayList<>();
        for (AttributePolicy definition : definitions.values()) {
            Object raw = supplied.get(definition.key());
            if (empty(raw)) {
                if (requireRequired && definition.required()) {
                    throw new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "CATEGORY_ATTRIBUTE_REQUIRED", "Attribute '" + definition.key() + "' is required.");
                }
                continue;
            }
            normalized.add(normalize(definition, raw));
        }
        return List.copyOf(normalized);
    }

    private NormalizedAttribute normalize(AttributePolicy definition, Object raw) {
        return switch (definition.type()) {
            case "TEXT" -> text(definition, raw);
            case "NUMBER" -> number(definition, raw);
            case "BOOLEAN" -> bool(definition, raw);
            case "ENUM" -> enumeration(definition, raw, false);
            case "MULTI_ENUM" -> enumeration(definition, raw, true);
            default -> throw new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CATEGORY_ATTRIBUTE_TYPE_UNSUPPORTED", "Attribute type is not supported.");
        };
    }

    private NormalizedAttribute text(AttributePolicy definition, Object raw) {
        if (!(raw instanceof String value)) {
            throw invalidValue(definition.key());
        }
        String normalized = value.trim();
        int min = integerRule(definition.validation(), "minLength", 0);
        int max = integerRule(definition.validation(), "maxLength", 1000);
        if (normalized.length() < min || normalized.length() > Math.min(max, 1000)) {
            throw invalidValue(definition.key());
        }
        return new NormalizedAttribute(definition.id(), normalized, null, null);
    }

    private NormalizedAttribute number(AttributePolicy definition, Object raw) {
        final BigDecimal value;
        try {
            value = raw instanceof BigDecimal decimal ? decimal : new BigDecimal(raw.toString());
        } catch (RuntimeException exception) {
            throw invalidValue(definition.key());
        }
        BigDecimal min = decimalRule(definition.validation(), "minimum");
        BigDecimal max = decimalRule(definition.validation(), "maximum");
        if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
            throw invalidValue(definition.key());
        }
        if (Boolean.TRUE.equals(definition.validation().get("integerOnly"))
                && value.stripTrailingZeros().scale() > 0) {
            throw invalidValue(definition.key());
        }
        return new NormalizedAttribute(definition.id(), null, value, null);
    }

    private NormalizedAttribute bool(AttributePolicy definition, Object raw) {
        if (!(raw instanceof Boolean value)) {
            throw invalidValue(definition.key());
        }
        return new NormalizedAttribute(definition.id(), null, null, value);
    }

    private NormalizedAttribute enumeration(AttributePolicy definition, Object raw, boolean multiple) {
        List<String> values;
        if (multiple) {
            if (!(raw instanceof Collection<?> collection)) throw invalidValue(definition.key());
            values = collection.stream().map(Object::toString).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
            if (values.isEmpty()) throw invalidValue(definition.key());
        } else {
            if (!(raw instanceof String value) || value.isBlank()) throw invalidValue(definition.key());
            values = List.of(value.trim());
        }
        Set<String> allowed = new LinkedHashSet<>(jdbc.queryForList("""
                select option_value from category_attribute_options
                where attribute_id=? and status='ACTIVE'
                """, String.class, definition.id()));
        if (!allowed.containsAll(values)) throw invalidValue(definition.key());
        String stored;
        try {
            stored = multiple ? objectMapper.writeValueAsString(values) : values.get(0);
        } catch (Exception exception) {
            throw invalidValue(definition.key());
        }
        return new NormalizedAttribute(definition.id(), stored, null, null);
    }

    private Map<String, Object> readAttributes(String listingId) {
        Map<String, Object> values = new LinkedHashMap<>();
        jdbc.query("""
                select d.attribute_key,d.data_type,a.string_value,a.number_value,a.boolean_value
                from listing_attributes a
                join category_attribute_definitions d on d.id=a.attribute_definition_id
                where a.listing_id=? order by d.display_order,d.attribute_key
                """, rs -> {
            String type = rs.getString("data_type");
            Object value = switch (type) {
                case "NUMBER" -> rs.getBigDecimal("number_value");
                case "BOOLEAN" -> rs.getBoolean("boolean_value");
                case "MULTI_ENUM" -> parseList(rs.getString("string_value"));
                default -> rs.getString("string_value");
            };
            values.put(rs.getString("attribute_key"), value);
        }, listingId);
        return Map.copyOf(values);
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, OBJECT_MAP); }
        catch (Exception ignored) { return Map.of(); }
    }

    private List<String> parseList(String json) {
        try { return objectMapper.readValue(json, STRING_LIST); }
        catch (Exception ignored) { return List.of(); }
    }

    private boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private int integerRule(Map<String, Object> rules, String key, int fallback) {
        Object value = rules.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private BigDecimal decimalRule(Map<String, Object> rules, String key) {
        Object value = rules.get(key);
        if (value == null) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private CatalogException invalidValue(String key) {
        return new CatalogException(HttpStatus.UNPROCESSABLE_ENTITY,
                "CATEGORY_ATTRIBUTE_INVALID", "Attribute '" + key + "' has an invalid value.");
    }

    public record ValidatedCatalogValues(long ruleVersion, List<NormalizedAttribute> attributes) { }
    public record NormalizedAttribute(String definitionId, String stringValue,
                                      BigDecimal numberValue, Boolean booleanValue) { }
    private record CategoryPolicy(String id, String status, String eligibility,
                                  boolean creationAllowed, boolean submissionAllowed, long ruleVersion) { }
    private record AttributePolicy(String id, String key, String type, boolean required,
                                   Map<String, Object> validation) { }
    private record ListingPolicy(String id, String categoryId, long ruleVersion) { }
}
