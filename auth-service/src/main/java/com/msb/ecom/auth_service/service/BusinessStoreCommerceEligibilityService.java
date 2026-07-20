package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.BusinessStoreCommerceEligibilityResponse;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessStoreCommerceEligibilityService {

    private final JdbcTemplate jdbcTemplate;
    private final InternalCommerceAuthenticator internalCommerceAuthenticator;

    public BusinessStoreCommerceEligibilityService(
            JdbcTemplate jdbcTemplate,
            InternalCommerceAuthenticator internalCommerceAuthenticator) {
        this.jdbcTemplate = jdbcTemplate;
        this.internalCommerceAuthenticator = internalCommerceAuthenticator;
    }

    @Transactional(readOnly = true)
    // Resolves current business and store eligibility for internal commerce validation.
    public BusinessStoreCommerceEligibilityResponse get(
            String suppliedToken,
            String businessId,
            String storeId) {
        internalCommerceAuthenticator.require(suppliedToken);
        String normalizedBusinessId = FixedLengthIds.requireTrimmed("Business ID", businessId, 26);
        String normalizedStoreId = FixedLengthIds.requireTrimmed("Store ID", storeId, 26);
        return jdbcTemplate.query("""
                        select b.id as business_id,
                               b.status as business_status,
                               s.id as store_id,
                               s.status as store_status
                        from businesses b
                        join stores s on s.business_id = b.id
                        where b.id = ?
                          and s.id = ?
                        """,
                (rs, rowNum) -> {
                    String businessStatus = rs.getString("business_status");
                    String storeStatus = rs.getString("store_status");
                    return new BusinessStoreCommerceEligibilityResponse(
                            rs.getString("business_id"),
                            rs.getString("store_id"),
                            "ACTIVE".equals(businessStatus) && "ACTIVE".equals(storeStatus),
                            businessStatus,
                            storeStatus);
                },
                normalizedBusinessId,
                normalizedStoreId)
                .stream()
                .findFirst()
                .orElseThrow(BusinessStoreNotFoundException::new);
    }

}
