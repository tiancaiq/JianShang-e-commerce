package com.msb.ecom.product_service.search.hybrid;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ListingHybridSearchRepository {

    private static final String PUBLIC_MEDIA_PATH = "/api/v1/public/listing-media/";

    private final JdbcTemplate jdbcTemplate;

    public ListingHybridSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Revalidates bounded fused IDs from authoritative Product tables and preserves fused order.
    public List<ListingHybridSearchListing> findCurrentEligibleByIds(List<String> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        if (listingIds.size() > ListingHybridRrf.MAX_FUSED_CANDIDATES) {
            throw new IllegalArgumentException("Too many hybrid listing candidates.");
        }
        String placeholders = String.join(",", listingIds.stream().map(id -> "?").toList());
        List<ListingHybridSearchListing> rows = jdbcTemplate.query("""
                select l.id,
                       l.version as listing_version,
                       l.category_id,
                       c.slug as category_slug,
                       c.name as category_name,
                       l.title,
                       l.description,
                       l.condition_code,
                       l.price_amount,
                       l.currency,
                       l.public_city,
                       l.public_region,
                       (l.quantity > 0) as available,
                       coalesce(l.published_at, l.updated_at) as published_at,
                       (
                         select li.id
                         from listing_images li
                         join listing_media_objects mo on mo.id = li.media_object_id
                         where li.listing_id = l.id
                           and li.moderation_status = 'APPROVED'
                           and mo.moderation_status = 'APPROVED'
                           and mo.upload_status = 'UPLOADED'
                         order by li.display_order asc, li.id asc
                         limit 1
                       ) as primary_image_id
                from listings l
                join categories c on c.id = l.category_id
                where l.seller_type = 'INDIVIDUAL'
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                  and l.id in (%s)
                  and not exists (
                    select 1 from enforcement_actions ea
                    join enforcement_action_scopes eas on eas.enforcement_action_id = ea.id
                    where ea.target_type = 'LISTING' and ea.target_id = l.id
                      and eas.scope = 'LISTING_PUBLIC_VISIBILITY' and ea.revoked_at is null
                      and ea.effective_at <= utc_timestamp(6)
                      and (ea.expires_at is null or ea.expires_at > utc_timestamp(6))
                  )
                """.formatted(placeholders),
                (resultSet, rowNumber) -> {
                    String imageId = resultSet.getString("primary_image_id");
                    return new ListingHybridSearchListing(
                            resultSet.getString("id"),
                            resultSet.getLong("listing_version"),
                            resultSet.getString("category_id"),
                            resultSet.getString("category_slug"),
                            resultSet.getString("category_name"),
                            resultSet.getString("title"),
                            resultSet.getString("condition_code"),
                            resultSet.getBigDecimal("price_amount"),
                            resultSet.getString("currency"),
                            resultSet.getString("public_city"),
                            resultSet.getString("public_region"),
                            resultSet.getBoolean("available"),
                            imageId == null ? null : PUBLIC_MEDIA_PATH + imageId,
                            resultSet.getTimestamp("published_at").toInstant(),
                            resultSet.getString("description"));
                },
                listingIds.toArray());
        Map<String, ListingHybridSearchListing> byId = new HashMap<>();
        for (ListingHybridSearchListing row : rows) {
            byId.put(row.listingId(), row);
        }
        return listingIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
