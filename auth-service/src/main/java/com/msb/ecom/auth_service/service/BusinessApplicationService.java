package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.BusinessApplicationDraftRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationDecisionRequest;
import com.msb.ecom.auth_service.dto.BusinessApplicationResponse;
import com.msb.ecom.auth_service.dto.BusinessVerificationWebhookRequest;
import com.msb.ecom.auth_service.model.BusinessApplication;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.BusinessApplicationRepository;
import com.msb.ecom.common.core.validation.TextInputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessApplicationService {

    private static final String DRAFT = "DRAFT";
    private static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    private static final String UNDER_REVIEW = "UNDER_REVIEW";
    private static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String APPROVE = "APPROVE";
    private static final String REJECT = "REJECT";
    private static final String REQUEST_INFORMATION = "REQUEST_INFORMATION";

    private final AuthService authService;
    private final BusinessApplicationRepository businessApplicationRepository;
    private final UlidGenerator ulidGenerator;
    private final JdbcTemplate jdbcTemplate;

    @Value("${business.verification.webhook-secret:local-business-verification-secret}")
    private String webhookSecret;

    @Transactional
    public BusinessApplicationResponse createDraft(BusinessApplicationDraftRequest request) {
        User user = authService.ensureUserEntity();
        if (businessApplicationRepository.existsByApplicantUserIdAndStatus(user.getId(), DRAFT)) {
            throw new BusinessApplicationConflictException("A draft business application already exists.");
        }

        BusinessApplication application = BusinessApplication.draft(
                ulidGenerator.next(),
                user.getId(),
                requiredText("Legal name", request.legalName(), 200),
                normalizedBusinessType(request.businessType()),
                normalizedCountry(request.country()),
                normalizedEmail(request.contactEmail()),
                normalizedPhone(request.contactPhone()),
                requiredText("Public city", request.publicCity(), 120),
                requiredText("Public region", request.publicRegion(), 120),
                normalizedUrl(request.websiteUrl()),
                optionalText("Description", request.description(), 1000));

        BusinessApplication saved = businessApplicationRepository.saveAndFlush(application);
        log.info("Created business application draft id={} applicantUserId={}", saved.getId(), user.getId());
        return BusinessApplicationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public BusinessApplicationResponse getOwned(String id) {
        User user = authService.ensureUserEntity();
        return businessApplicationRepository.findByIdAndApplicantUserId(id, user.getId())
                .map(BusinessApplicationResponse::from)
                .orElseThrow(BusinessApplicationNotFoundException::new);
    }

    @Transactional
    public BusinessApplicationResponse updateDraft(
            String id,
            Long expectedVersion,
            BusinessApplicationDraftRequest request) {
        User user = authService.ensureUserEntity();
        BusinessApplication application = businessApplicationRepository.findByIdAndApplicantUserId(id, user.getId())
                .orElseThrow(BusinessApplicationNotFoundException::new);

        if (!DRAFT.equals(application.getStatus())) {
            throw new BusinessApplicationConflictException("Only draft business applications can be updated.");
        }
        requireCurrentVersion(application, expectedVersion);

        application.updateDraft(
                requiredText("Legal name", request.legalName(), 200),
                normalizedBusinessType(request.businessType()),
                normalizedCountry(request.country()),
                normalizedEmail(request.contactEmail()),
                normalizedPhone(request.contactPhone()),
                requiredText("Public city", request.publicCity(), 120),
                requiredText("Public region", request.publicRegion(), 120),
                normalizedUrl(request.websiteUrl()),
                optionalText("Description", request.description(), 1000));

        return BusinessApplicationResponse.from(businessApplicationRepository.saveAndFlush(application));
    }

    @Transactional
    public BusinessApplicationResponse submit(String id, Long expectedVersion) {
        User user = authService.ensureUserEntity();
        BusinessApplication application = businessApplicationRepository.findByIdAndApplicantUserId(id, user.getId())
                .orElseThrow(BusinessApplicationNotFoundException::new);

        if (!DRAFT.equals(application.getStatus())) {
            throw new BusinessApplicationConflictException("Only draft business applications can be submitted.");
        }
        requireCurrentVersion(application, expectedVersion);

        requireCompleteForSubmit(application);
        application.submit(Instant.now());
        BusinessApplication submitted = businessApplicationRepository.saveAndFlush(application);

        log.info("Submitted business application id={} applicantUserId={}", submitted.getId(), user.getId());
        return BusinessApplicationResponse.from(submitted);
    }

    @Transactional
    public BusinessApplicationResponse applyVerificationWebhook(
            BusinessVerificationWebhookRequest request,
            String signature,
            String rawBody) {
        verifyWebhookSignature(signature, rawBody);

        String outcome = normalizedWebhookOutcome(request.outcome());
        BusinessApplication application = businessApplicationRepository.findById(request.applicationId())
                .orElseThrow(BusinessApplicationNotFoundException::new);

        try {
            insertVerificationEvent(
                    request.eventId(),
                    application.getId(),
                    "PROVIDER",
                    "BUSINESS_VERIFICATION_CALLBACK",
                    outcome,
                    optionalText("Reason", request.reason(), 1000),
                    sha256Hex(rawBody),
                    null);
        } catch (DuplicateKeyException exception) {
            log.info("Ignored duplicate business verification callback eventId={}", request.eventId());
            return BusinessApplicationResponse.from(application);
        }

        if (PENDING_VERIFICATION.equals(application.getStatus())) {
            application.applyProviderOutcome(outcome);
        }

        BusinessApplication saved = businessApplicationRepository.saveAndFlush(application);
        log.info("Processed business verification callback applicationId={} outcome={}", saved.getId(), outcome);
        return BusinessApplicationResponse.from(saved);
    }

    @Transactional
    public BusinessApplicationResponse decide(
            String id,
            Long expectedVersion,
            BusinessApplicationDecisionRequest request) {
        User reviewer = authService.ensureUserEntity();
        String reviewerUserId = requirePlatformAdminUserId(reviewer);

        BusinessApplication application = businessApplicationRepository.findById(id)
                .orElseThrow(BusinessApplicationNotFoundException::new);
        requireCurrentVersion(application, expectedVersion);
        requireReviewableForAdminDecision(application);

        String decision = normalizedDecision(request.decision());
        String reason = requiredText("Reason", request.reason(), 1000);
        Instant now = Instant.now();

        if (APPROVE.equals(decision)) {
            String businessId = ulidGenerator.next();
            insertApprovedBusiness(application, reviewerUserId, businessId, now);
            insertOwnerMembership(businessId, application.getApplicantUserId(), reviewerUserId, now);
            application.approve(reviewerUserId, businessId, reason, now);
        } else if (REJECT.equals(decision)) {
            application.reject(reviewerUserId, reason, now);
        } else {
            application.requestInformation(reviewerUserId, reason, now);
        }

        insertVerificationEvent(
                null,
                application.getId(),
                "ADMIN",
                "BUSINESS_APPLICATION_DECISION",
                decision,
                reason,
                null,
                reviewerUserId);

        BusinessApplication saved = businessApplicationRepository.saveAndFlush(application);
        log.info("Admin business application decision id={} reviewerUserId={} decision={}",
                saved.getId(), reviewerUserId, decision);
        return BusinessApplicationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    // Lists only business applications that are active admin work for ADM-BUS-01.
    public List<BusinessApplicationResponse> listAdminReviewQueue(String status) {
        requirePlatformAdminUserId(authService.ensureUserEntity());

        List<String> statuses = status == null || status.isBlank()
                ? List.of(PENDING_VERIFICATION, UNDER_REVIEW)
                : List.of(normalizedReviewQueueStatus(status));
        return businessApplicationRepository.findByStatusInOrderBySubmittedAtAscIdAsc(statuses).stream()
                .map(BusinessApplicationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    // Provides the admin review detail record without requiring applicant ownership.
    public BusinessApplicationResponse getAdminApplication(String id) {
        requirePlatformAdminUserId(authService.ensureUserEntity());
        return businessApplicationRepository.findById(id)
                .map(BusinessApplicationResponse::from)
                .orElseThrow(BusinessApplicationNotFoundException::new);
    }

    private void requireCompleteForSubmit(BusinessApplication application) {
        requiredText("Legal name", application.getLegalName(), 200);
        normalizedBusinessType(application.getBusinessType());
        normalizedCountry(application.getCountry());
        normalizedEmail(application.getContactEmail());
        requiredText("Public city", application.getPublicCity(), 120);
        requiredText("Public region", application.getPublicRegion(), 120);
    }

    private void verifyWebhookSignature(String signature, String rawBody) {
        String value = signature == null ? "" : signature.trim();
        if (value.startsWith("sha256=")) {
            value = value.substring("sha256=".length());
        }
        if (!value.matches("^[0-9a-fA-F]{64}$")) {
            log.warn("Denied business verification webhook reason=invalid_signature_format");
            throw new BusinessApplicationForbiddenException();
        }
        String expected = hmacSha256Hex(rawBody);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            log.warn("Denied business verification webhook reason=signature_mismatch");
            throw new BusinessApplicationForbiddenException();
        }
    }

    private String normalizedWebhookOutcome(String outcome) {
        String normalized = requiredText("Outcome", outcome, 64).toUpperCase(Locale.ROOT);
        if (!UNDER_REVIEW.equals(normalized) && !VERIFICATION_FAILED.equals(normalized)) {
            throw new IllegalArgumentException("Verification outcome must be UNDER_REVIEW or VERIFICATION_FAILED");
        }
        return normalized;
    }

    private String normalizedDecision(String decision) {
        String normalized = requiredText("Decision", decision, 32).toUpperCase(Locale.ROOT);
        if (!APPROVE.equals(normalized)
                && !REJECT.equals(normalized)
                && !REQUEST_INFORMATION.equals(normalized)) {
            throw new IllegalArgumentException("Decision must be APPROVE, REJECT, or REQUEST_INFORMATION");
        }
        return normalized;
    }

    private String normalizedReviewQueueStatus(String status) {
        String normalized = requiredText("Status", status, 32).toUpperCase(Locale.ROOT);
        if (!PENDING_VERIFICATION.equals(normalized) && !UNDER_REVIEW.equals(normalized)) {
            throw new IllegalArgumentException("Status must be PENDING_VERIFICATION or UNDER_REVIEW");
        }
        return normalized;
    }

    private void requirePlatformAdmin(String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_roles
                where user_id = ? and role_id = ?
                """, Integer.class, userId, PLATFORM_ADMIN_ROLE);
        if (count == null || count == 0) {
            log.warn("Denied business application admin action userId={} reason=missing_platform_admin_role", userId);
            throw new BusinessApplicationForbiddenException();
        }
    }

    private String requirePlatformAdminUserId(User user) {
        requirePlatformAdmin(user.getId());
        return user.getId();
    }

    private void requireCurrentVersion(BusinessApplication application, Long expectedVersion) {
        if (application.getVersion() != expectedVersion) {
            throw new BusinessApplicationVersionConflictException();
        }
    }

    private void requireReviewableForAdminDecision(BusinessApplication application) {
        if (!PENDING_VERIFICATION.equals(application.getStatus()) && !UNDER_REVIEW.equals(application.getStatus())) {
            throw new BusinessApplicationConflictException(
                    "Only pending or under-review business applications can receive an admin decision.");
        }
    }

    private void insertApprovedBusiness(
            BusinessApplication application,
            String reviewerUserId,
            String businessId,
            Instant now) {
        jdbcTemplate.update("""
                insert into businesses (
                    id, application_id, legal_name, business_type, country, status,
                    approved_at, approved_by, version, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0, ?, ?)
                """,
                businessId,
                application.getId(),
                application.getLegalName(),
                application.getBusinessType(),
                application.getCountry(),
                Timestamp.from(now),
                reviewerUserId,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertOwnerMembership(String businessId, String ownerUserId, String reviewerUserId, Instant now) {
        jdbcTemplate.update("""
                insert into business_memberships (
                    business_id, user_id, role, status, invited_by, created_at, updated_at
                )
                values (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?)
                """,
                businessId,
                ownerUserId,
                reviewerUserId,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertVerificationEvent(
            String providerEventId,
            String applicationId,
            String source,
            String eventType,
            String outcome,
            String reason,
            String payloadHash,
            String actorUserId) {
        jdbcTemplate.update("""
                insert into business_verification_events (
                    id, provider_event_id, application_id, source, event_type, outcome,
                    reason, payload_hash, actor_user_id, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ulidGenerator.next(),
                providerEventId,
                applicationId,
                source,
                eventType,
                outcome,
                reason,
                payloadHash,
                actorUserId,
                Timestamp.from(Instant.now()));
    }

    private String hmacSha256Hex(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Business verification webhook signature cannot be calculated", exception);
        }
    }

    private String sha256Hex(String rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String requiredText(String fieldName, String value, int maxLength) {
        String normalized = optionalText(fieldName, value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String optionalText(String fieldName, String value, int maxLength) {
        String trimmed = TextInputs.collapseWhitespaceToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return trimmed;
    }

    private String normalizedBusinessType(String businessType) {
        String normalized = requiredText("Business type", businessType, 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z_]+$")) {
            throw new IllegalArgumentException("Business type is invalid");
        }
        return normalized;
    }

    private String normalizedCountry(String country) {
        String normalized = requiredText("Country", country, 2).toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("Country must be ISO 3166-1 alpha-2");
        }
        return normalized;
    }

    private String normalizedEmail(String email) {
        String normalized = requiredText("Contact email", email, 320).toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Contact email is invalid");
        }
        return normalized;
    }

    private String normalizedPhone(String phone) {
        String normalized = optionalText("Contact phone", phone, 32);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("Contact phone must be E.164 formatted");
        }
        return normalized;
    }

    private String normalizedUrl(String url) {
        String normalized = optionalText("Website URL", url, 2048);
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Website URL must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Website URL must include a host");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Website URL is invalid");
        }
        return normalized;
    }
}
