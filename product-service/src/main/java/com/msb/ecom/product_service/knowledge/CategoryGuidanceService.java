package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CategoryGuidanceService {

    private static final Pattern LANGUAGE =
            Pattern.compile("[a-z]{2,3}(?:-[a-z0-9]{2,8})*");
    private static final Pattern HTML =
            Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final String PRODUCER = "product-service";

    private final CategoryGuidanceRepository repository;
    private final ListingKnowledgeRepository outboxRepository;
    private final CategoryGuidanceCanonicalHasher canonicalHasher;
    private final CurrentActorProvider currentActorProvider;
    private final AuthServiceClient authServiceClient;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final CategoryGuidanceMetrics metrics;

    public CategoryGuidanceService(
            CategoryGuidanceRepository repository,
            ListingKnowledgeRepository outboxRepository,
            CategoryGuidanceCanonicalHasher canonicalHasher,
            CurrentActorProvider currentActorProvider,
            AuthServiceClient authServiceClient,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper,
            CategoryGuidanceMetrics metrics,
            @Value("${category.guidance.publication.topic:category-guidance-v1}") String topic) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.canonicalHasher = canonicalHasher;
        this.currentActorProvider = currentActorProvider;
        this.authServiceClient = authServiceClient;
        this.ulidGenerator = ulidGenerator;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.topic = topic;
    }

    @Transactional
    // Publishes a new immutable source version and reference event under one row lock and transaction.
    public CategoryGuidanceSourceResponse publish(
            String categoryId,
            String language,
            long expectedVersion,
            CategoryGuidancePublishRequest request) {
        AdminActor actor = requireAdmin();
        String normalizedCategoryId = categoryId(categoryId);
        String normalizedLanguage = language(language);
        String title = text("Title", request == null ? null : request.title(), 180);
        String body = text("Body", request == null ? null : request.body(), 12_000);
        CategoryGuidanceRepository.CategorySnapshot category = repository.lockCategory(normalizedCategoryId)
                .orElseThrow(CategoryNotFoundException::new);
        if (!"ACTIVE".equals(category.status())) {
            throw new CategoryGuidanceCategoryInactiveException();
        }

        CategoryGuidanceVersion latest =
                repository.findLatestForUpdate(normalizedCategoryId, normalizedLanguage).orElse(null);
        requireExpected(expectedVersion, latest);
        long sourceVersion = latest == null ? 1 : latest.sourceVersion() + 1;
        Long supersedes = latest == null ? null : latest.sourceVersion();
        Instant now = Instant.now();
        String correlationId = correlationId();
        CategoryGuidanceVersion version = new CategoryGuidanceVersion(
                normalizedCategoryId,
                normalizedLanguage,
                sourceVersion,
                supersedes,
                "ACTIVE",
                "PUBLIC",
                category.slug(),
                category.name(),
                title,
                body,
                canonicalHasher.hash(
                        normalizedCategoryId,
                        category.slug(),
                        category.name(),
                        normalizedLanguage,
                        title,
                        body),
                now,
                null,
                actor.userId(),
                correlationId,
                now);
        repository.insertActive(version);
        insertOutbox(
                version,
                latest == null || "INVALIDATED".equals(latest.lifecycle())
                        ? "category-guidance.activated"
                        : "category-guidance.updated");
        log.info(
                "Category guidance published categoryId={} language={} sourceVersion={} adminUserId={} correlationId={}",
                normalizedCategoryId,
                normalizedLanguage,
                sourceVersion,
                actor.userId(),
                correlationId);
        return CategoryGuidanceSourceResponse.from(version);
    }

    @Transactional
    // Retires an active stream by appending a newer tombstone instead of mutating history.
    public CategoryGuidanceSourceResponse retire(
            String categoryId,
            String language,
            long expectedVersion) {
        AdminActor actor = requireAdmin();
        String normalizedCategoryId = categoryId(categoryId);
        String normalizedLanguage = language(language);
        repository.lockCategory(normalizedCategoryId).orElseThrow(CategoryNotFoundException::new);
        CategoryGuidanceVersion latest =
                repository.findLatestForUpdate(normalizedCategoryId, normalizedLanguage).orElse(null);
        requireExpected(expectedVersion, latest);
        if (latest == null || !"ACTIVE".equals(latest.lifecycle())) {
            throw new CategoryGuidanceVersionConflictException();
        }
        CategoryGuidanceVersion tombstone = tombstone(
                latest,
                actor.userId(),
                correlationId(),
                Instant.now());
        repository.insertInvalidated(tombstone);
        insertOutbox(tombstone, "category-guidance.invalidated");
        log.info(
                "Category guidance retired categoryId={} language={} sourceVersion={} adminUserId={} correlationId={}",
                normalizedCategoryId,
                normalizedLanguage,
                tombstone.sourceVersion(),
                actor.userId(),
                tombstone.correlationId());
        return CategoryGuidanceSourceResponse.from(tombstone);
    }

    @Transactional(readOnly = true)
    public CategoryGuidanceSourceResponse current(String categoryId, String language) {
        requireAdmin();
        String normalizedCategoryId = categoryId(categoryId);
        String normalizedLanguage = language(language);
        if (repository.findCategory(normalizedCategoryId).isEmpty()) {
            throw new CategoryNotFoundException();
        }
        return repository.findLatest(normalizedCategoryId, normalizedLanguage)
                .map(CategoryGuidanceSourceResponse::from)
                .orElseThrow(CategoryGuidanceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public CategoryGuidancePageResponse history(
            String categoryId,
            String language,
            String cursor,
            Integer requestedLimit) {
        requireAdmin();
        String normalizedCategoryId = categoryId(categoryId);
        String normalizedLanguage = language(language);
        if (repository.findCategory(normalizedCategoryId).isEmpty()) {
            throw new CategoryNotFoundException();
        }
        int limit = limit(requestedLimit);
        Long beforeVersion = decodeHistoryCursor(cursor);
        List<CategoryGuidanceVersion> fetched =
                repository.findHistory(normalizedCategoryId, normalizedLanguage, beforeVersion, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<CategoryGuidanceVersion> page = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("v1|" + page.get(page.size() - 1).sourceVersion()).getBytes(StandardCharsets.UTF_8))
                : null;
        return new CategoryGuidancePageResponse(
                page.stream().map(CategoryGuidanceSourceResponse::from).toList(),
                nextCursor,
                hasMore,
                null);
    }

    @Transactional
    // Lets a future category-status command invalidate every active language in its own transaction.
    public int invalidateForCategoryDeactivation(
            String categoryId,
            String actorUserId,
            String correlationId,
            Instant occurredAt) {
        String normalizedCategoryId = categoryId(categoryId);
        repository.lockCategory(normalizedCategoryId).orElseThrow(CategoryNotFoundException::new);
        List<CategoryGuidanceVersion> active =
                repository.findLatestActiveForCategoryForUpdate(normalizedCategoryId);
        for (CategoryGuidanceVersion latest : active) {
            CategoryGuidanceVersion tombstone =
                    tombstone(latest, actorUserId, correlationId, occurredAt);
            repository.insertInvalidated(tombstone);
            insertOutbox(tombstone, "category-guidance.invalidated");
        }
        metrics.categoryDeactivation("success");
        log.info(
                "Category guidance invalidated for category deactivation categoryId={} languages={} actorUserId={} correlationId={}",
                normalizedCategoryId,
                active.size(),
                actorUserId,
                correlationId);
        return active.size();
    }

    private CategoryGuidanceVersion tombstone(
            CategoryGuidanceVersion latest,
            String actorUserId,
            String correlationId,
            Instant occurredAt) {
        return new CategoryGuidanceVersion(
                latest.categoryId(),
                latest.language(),
                latest.sourceVersion() + 1,
                latest.sourceVersion(),
                "INVALIDATED",
                "PUBLIC",
                null,
                null,
                null,
                null,
                null,
                null,
                occurredAt,
                FixedLengthIds.requireTrimmed("Actor user ID", actorUserId, 26),
                requiredCorrelationId(correlationId),
                occurredAt);
    }

    private void insertOutbox(CategoryGuidanceVersion version, String eventType) {
        String eventId = ulidGenerator.next();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", "CATEGORY_GUIDANCE");
        payload.put("sourceId", version.categoryId());
        payload.put("sourceVersion", Long.toString(version.sourceVersion()));
        payload.put("knowledgeLifecycle", version.lifecycle());
        payload.put("supersedesVersion", version.supersedesVersion() == null
                ? null
                : Long.toString(version.supersedesVersion()));
        payload.put("language", version.language());
        ListingKnowledgeOutboxEvent event = new ListingKnowledgeOutboxEvent(
                eventId,
                topic,
                version.categoryId() + ":" + version.language(),
                "category-guidance",
                version.categoryId(),
                eventType,
                1,
                PRODUCER,
                version.createdAt(),
                version.correlationId(),
                json(payload),
                0,
                version.createdAt());
        outboxRepository.insertOutbox(
                event,
                "CATEGORY_GUIDANCE:" + version.categoryId() + ":"
                        + version.language() + ":" + version.sourceVersion());
    }

    private AdminActor requireAdmin() {
        CurrentActor current = currentActorProvider.currentActor();
        AuthServiceClient.PlatformAdminAuthorization admin =
                authServiceClient.requirePlatformAdmin(current.accessToken());
        return new AdminActor(admin.userId());
    }

    private void requireExpected(long expected, CategoryGuidanceVersion latest) {
        long actual = latest == null ? 0 : latest.sourceVersion();
        if (expected < 0 || expected != actual) {
            throw new CategoryGuidanceVersionConflictException();
        }
    }

    private String categoryId(String value) {
        return FixedLengthIds.requireTrimmed("Category ID", value, 26);
    }

    private String language(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGE.matcher(normalized).matches() || normalized.length() > 35) {
            throw new IllegalArgumentException("Language must be a valid normalized language tag.");
        }
        return normalized;
    }

    private String text(String label, String value, int maxLength) {
        String normalized = value == null
                ? ""
                : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " must contain between 1 and " + maxLength + " characters.");
        }
        if (normalized.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')) {
            throw new IllegalArgumentException(label + " contains unsupported control characters.");
        }
        if (HTML.matcher(normalized).find()) {
            throw new IllegalArgumentException(label + " must be plain text without HTML.");
        }
        return normalized;
    }

    private int limit(Integer requested) {
        int normalized = requested == null ? DEFAULT_LIMIT : requested;
        if (normalized < 1 || normalized > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and 200.");
        }
        return normalized;
    }

    private Long decodeHistoryCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            long version = Long.parseLong(parts[1]);
            if (version <= 0) {
                throw new IllegalArgumentException();
            }
            return version;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid category guidance history cursor.");
        }
    }

    private String correlationId() {
        return requiredCorrelationId(MDC.get("correlationId"));
    }

    private String requiredCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            return ulidGenerator.next();
        }
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Category guidance event could not be serialized.", exception);
        }
    }

    private record AdminActor(String userId) {
    }
}
