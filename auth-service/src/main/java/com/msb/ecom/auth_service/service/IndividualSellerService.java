package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.ActivateIndividualSellerRequest;
import com.msb.ecom.auth_service.dto.IndividualSellerProfileResponse;
import com.msb.ecom.auth_service.model.IndividualSellerProfile;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.IndividualSellerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndividualSellerService {

    private static final String INDIVIDUAL_SELLER_ROLE = "INDIVIDUAL_SELLER";
    private static final String CURRENT_TERMS_VERSION = "2026-01";

    private final AuthService authService;
    private final IndividualSellerProfileRepository individualSellerProfileRepository;
    private final UlidGenerator ulidGenerator;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public IndividualSellerProfileResponse activate(Jwt jwt, ActivateIndividualSellerRequest request) {
        User user = authService.ensureUserEntity(jwt);

        if (individualSellerProfileRepository.existsByUserId(user.getId())) {
            throw new IndividualSellerAlreadyActiveException();
        }

        String termsVersion = normalizedTermsVersion(request.termsVersion());
        IndividualSellerProfile profile = IndividualSellerProfile.activate(
                ulidGenerator.next(),
                user.getId(),
                normalizedLocation("Public city", request.publicCity()),
                normalizedLocation("Public region", request.publicRegion()),
                termsVersion);

        IndividualSellerProfile saved = individualSellerProfileRepository.saveAndFlush(profile);
        grantIndividualSellerRole(user.getId());

        log.info("Activated individual seller profile id={} userId={}", saved.getId(), user.getId());
        return IndividualSellerProfileResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public IndividualSellerProfileResponse getCurrentProfile(Jwt jwt) {
        User user = authService.ensureUserEntity(jwt);
        return individualSellerProfileRepository.findByUserId(user.getId())
                .map(IndividualSellerProfileResponse::from)
                .orElseThrow(IndividualSellerProfileNotFoundException::new);
    }

    private void grantIndividualSellerRole(String userId) {
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_id, granted_by, granted_at)
                values (?, ?, null, ?)
                on duplicate key update granted_at = granted_at
                """, userId, INDIVIDUAL_SELLER_ROLE, Timestamp.from(Instant.now()));
    }

    private String normalizedLocation(String fieldName, String value) {
        String trimmed = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (trimmed.length() > 120) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return trimmed;
    }

    private String normalizedTermsVersion(String termsVersion) {
        String trimmed = termsVersion == null ? "" : termsVersion.trim();
        if (!CURRENT_TERMS_VERSION.equals(trimmed)) {
            throw new IllegalArgumentException("Seller terms version must be " + CURRENT_TERMS_VERSION);
        }
        return trimmed;
    }
}
