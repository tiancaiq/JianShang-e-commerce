package com.msb.ecom.product_service.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {
    private static final int MAX_DEPTH = 3;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,79}");
    private static final Pattern OPTION = Pattern.compile("[a-z0-9][a-z0-9_-]{0,119}");
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private final CatalogRepository repository;
    private final CurrentActorProvider currentActorProvider;
    private final AuthServiceClient authServiceClient;
    private final CatalogGovernanceClient governanceClient;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public CatalogOverview overview(String query, CategoryStatus status, SellerEligibility eligibility) {
        require(AdminPermission.CATALOG_READ);
        return new CatalogOverview(repository.search(query, status, eligibility),
                repository.countByStatus("ACTIVE"), repository.countByStatus("DISABLED"),
                repository.countByStatus("DEPRECATED"), repository.activeAttributeCount(),
                repository.activeGuidanceCount());
    }

    @Transactional(readOnly = true)
    public CategoryDetail detail(String categoryId) {
        AdminContext context = require(AdminPermission.CATALOG_READ);
        return detail(categoryId(categoryId), context);
    }

    @Transactional
    public CategoryDetail create(String idempotencyKey, CreateCategoryRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_CATEGORY_MANAGE);
        String key = idempotencyKey(idempotencyKey);
        String hash = hash(json(request));
        CatalogRepository.IdempotencyRow replay = repository.idempotency(context.userId(), "CATEGORY_CREATE", key)
                .orElse(null);
        if (replay != null) return replay(replay, hash, context);

        String name = text("Category name", request == null ? null : request.name(), 160);
        String slug = slug(name);
        if (repository.findBySlug(slug).isPresent()) {
            throw CatalogException.conflict("CATEGORY_SLUG_CONFLICT", "A category already uses this slug.");
        }
        String parentId = optionalId(request.parentId());
        requireParent(null, parentId);
        CategoryStatus status = request.status() == null ? CategoryStatus.ACTIVE : request.status();
        SellerEligibility eligibility = request.sellerEligibility() == null
                ? SellerEligibility.BOTH : request.sellerEligibility();
        Instant now = Instant.now();
        String id = ulidGenerator.next();
        boolean allowed = status == CategoryStatus.ACTIVE && eligibility != SellerEligibility.NONE;
        CatalogRepository.CategoryRow row = new CatalogRepository.CategoryRow(id, parentId, slug, name,
                optionalText(request.description(), 1000), status, eligibility, allowed, allowed, null,
                order(request.displayOrder()), 1, 0, now, now);
        try {
            repository.insertCategory(row);
            publishCurrentRule(id, "Initial catalog configuration", context, now);
            event(id, "CATEGORY_CREATED", context, "CATEGORY", id, null, json(row),
                    "Category created", Map.of("slug", slug), now);
            repository.insertIdempotency(ulidGenerator.next(), context.userId(), "CATEGORY_CREATE", key,
                    hash, id, now);
        } catch (DuplicateKeyException exception) {
            throw CatalogException.conflict("CATEGORY_SLUG_CONFLICT", "A category already uses this slug.");
        }
        return detail(id, context);
    }

    @Transactional
    public CategoryDetail update(String categoryId, UpdateCategoryRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_CATEGORY_MANAGE);
        CatalogRepository.CategoryRow before = locked(categoryId);
        if (request.sellerEligibility() != null && request.sellerEligibility() != before.sellerEligibility()) {
            throw CatalogException.invalid("CATEGORY_POLICY_CHANGE_NOT_ALLOWED",
                    "Change seller eligibility through the reviewed policy action.");
        }
        String name = text("Category name", request.name(), 160);
        Instant now = Instant.now();
        int updated = repository.updateMetadata(before.id(), request.expectedVersion(), name,
                optionalText(request.description(), 1000), order(request.displayOrder()),
                before.sellerEligibility(), now);
        version(updated, "CATEGORY_VERSION_CONFLICT");
        event(before.id(), "CATEGORY_UPDATED", context, "CATEGORY", before.id(), json(before),
                json(repository.find(before.id()).orElseThrow()), reason(request.reason()), Map.of(), now);
        return detail(before.id(), context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview movePreview(String categoryId, MoveCategoryRequest request) {
        require(AdminPermission.CATALOG_CATEGORY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        requireParent(category.id(), optionalId(request.parentId()));
        return preview(category, "CATEGORY_MOVE", category.parentId(), optionalId(request.parentId()), 0);
    }

    @Transactional
    public CategoryDetail move(String categoryId, MoveCategoryRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_CATEGORY_MANAGE);
        CatalogRepository.CategoryRow before = locked(categoryId);
        String parent = optionalId(request.parentId());
        requireParent(before.id(), parent);
        version(repository.move(before.id(), request.expectedVersion(), parent, Instant.now()),
                "CATEGORY_VERSION_CONFLICT");
        Instant now = Instant.now();
        event(before.id(), "CATEGORY_MOVED", context, "CATEGORY", before.id(), json(before),
                json(repository.find(before.id()).orElseThrow()), reason(request.reason()), Map.of(), now);
        return detail(before.id(), context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview statusPreview(String categoryId, ChangeCategoryStatusRequest request) {
        require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        return preview(category, "CATEGORY_STATUS", category.status().name(), required(request.status(), "Status").name(), 0);
    }

    // Uses the Product-owned impact preview and performs no catalog mutation while approval is pending.
    @Transactional(readOnly = true)
    public CatalogApproval evaluateStatusGovernance(String categoryId, String idempotencyKey,
                                                    ChangeCategoryStatusRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_POLICY_MANAGE);
        String key = idempotencyKey(idempotencyKey);
        CatalogRepository.CategoryRow row = found(categoryId);
        CategoryStatus proposed = required(request.status(), "Status");
        if (proposed != CategoryStatus.DISABLED || proposed == row.status()) {
            return new CatalogApproval("APPROVAL_NOT_REQUIRED", null, false,
                    "This category status change does not require governance approval.");
        }
        CategorySummary category = repository.findSummary(row.id()).orElseThrow();
        ImpactPreview impact = preview(row, "CATEGORY_STATUS", row.status().name(), proposed.name(), 0);
        return governanceClient.evaluateCategoryDisable(context.accessToken(), category, impact, request, key);
    }

    @Transactional
    public CategoryDetail changeStatus(String categoryId, String idempotencyKey,
                                       ChangeCategoryStatusRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_POLICY_MANAGE);
        String key = idempotencyKey(idempotencyKey);
        String hash = hash(json(request));
        CatalogRepository.IdempotencyRow replay = repository.idempotency(context.userId(), "CATEGORY_STATUS", key)
                .orElse(null);
        if (replay != null) return replay(replay, hash, context);
        CatalogRepository.CategoryRow before = locked(categoryId);
        CategoryStatus status = required(request.status(), "Status");
        if (status == before.status()) return detail(before.id(), context);
        Instant now = Instant.now();
        version(repository.updateStatus(before.id(), request.expectedVersion(), status, now),
                "CATEGORY_VERSION_CONFLICT");
        publishCurrentRule(before.id(), "Category status changed: " + reason(request.reason()), context, now);
        event(before.id(), "CATEGORY_" + status.name(), context, "CATEGORY", before.id(), json(before),
                json(repository.find(before.id()).orElseThrow()), reason(request.reason()),
                Map.of("hiddenCascade", "none"), now);
        repository.insertIdempotency(ulidGenerator.next(), context.userId(), "CATEGORY_STATUS", key,
                hash, before.id(), now);
        return detail(before.id(), context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview policyPreview(String categoryId, PublishPolicyRequest request) {
        require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        String proposed = String.join("/", required(request.sellerEligibility(), "Seller eligibility").name(),
                String.valueOf(Boolean.TRUE.equals(request.listingCreationAllowed())),
                String.valueOf(Boolean.TRUE.equals(request.listingSubmissionAllowed())));
        String current = String.join("/", category.sellerEligibility().name(),
                String.valueOf(category.creationAllowed()), String.valueOf(category.submissionAllowed()));
        return preview(category, "CATALOG_POLICY", current, proposed, 0);
    }

    @Transactional
    public CategoryDetail publishPolicy(String categoryId, PublishPolicyRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow before = locked(categoryId);
        SellerEligibility eligibility = required(request.sellerEligibility(), "Seller eligibility");
        boolean creation = Boolean.TRUE.equals(request.listingCreationAllowed());
        boolean submission = Boolean.TRUE.equals(request.listingSubmissionAllowed());
        if (before.status() != CategoryStatus.ACTIVE && (creation || submission)) {
            throw CatalogException.invalid("CATEGORY_POLICY_CHANGE_NOT_ALLOWED",
                    "Disabled or deprecated categories cannot allow listing creation or submission.");
        }
        String replacement = optionalId(request.replacementCategoryId());
        if (replacement != null && replacement.equals(before.id())) {
            throw CatalogException.invalid("CATEGORY_PARENT_INVALID", "A category cannot replace itself.");
        }
        if (replacement != null) found(replacement);
        Instant now = Instant.now();
        version(repository.updatePolicy(before.id(), request.expectedVersion(), eligibility, creation,
                submission, replacement, now), "CATEGORY_VERSION_CONFLICT");
        publishCurrentRule(before.id(), reason(request.reason()), context, now);
        event(before.id(), "CATALOG_POLICY_UPDATED", context, "CATEGORY", before.id(), json(before),
                json(repository.find(before.id()).orElseThrow()), reason(request.reason()), Map.of(), now);
        return detail(before.id(), context);
    }

    @Transactional
    public CategoryDetail createAttribute(String categoryId, String idempotencyKey,
                                          CreateAttributeRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        String key = idempotencyKey(idempotencyKey);
        String hash = hash(json(request));
        CatalogRepository.IdempotencyRow replay = repository.idempotency(context.userId(), "ATTRIBUTE_CREATE", key)
                .orElse(null);
        if (replay != null) return replay(replay, hash, context);
        CatalogRepository.CategoryRow category = locked(categoryId);
        String attributeKey = text("Attribute key", request.key(), 80).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(attributeKey).matches()) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID", "Attribute key format is invalid.");
        }
        AttributeType type = required(request.dataType(), "Attribute type");
        validateConfiguration(type, request.validation(), request.options());
        if ((type == AttributeType.ENUM || type == AttributeType.MULTI_ENUM)
                && (request.options() == null || request.options().isEmpty())) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID",
                    "Enum attributes require at least one option.");
        }
        Instant now = Instant.now();
        String id = ulidGenerator.next();
        CatalogRepository.AttributeRow row = new CatalogRepository.AttributeRow(id, category.id(), attributeKey,
                text("Attribute label", request.label(), 160), optionalText(request.description(), 1000), type,
                request.required(), request.searchable(), request.filterable(), json(request.validation()),
                order(request.displayOrder()), AttributeStatus.ACTIVE, 0, now, now);
        try {
            repository.insertAttribute(row);
            insertOptions(id, request.options(), now);
            repository.bumpRule(category.id(), now);
            publishCurrentRule(category.id(), reason(request.reason()), context, now);
            event(category.id(), "ATTRIBUTE_CREATED", context, "ATTRIBUTE", id, null, json(row),
                    reason(request.reason()), Map.of("key", attributeKey), now);
            repository.insertIdempotency(ulidGenerator.next(), context.userId(), "ATTRIBUTE_CREATE", key,
                    hash, category.id(), now);
        } catch (DuplicateKeyException exception) {
            throw CatalogException.conflict("CATEGORY_ATTRIBUTE_KEY_CONFLICT",
                    "This category already uses the attribute key.");
        }
        return detail(category.id(), context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview attributePreview(String categoryId, String attributeId,
                                          UpdateAttributeRequest request) {
        require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        CatalogRepository.AttributeRow attribute = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        long missing = !attribute.required() && request.required()
                ? repository.missingRequired(category.id(), attribute.id()) : 0;
        return preview(category, "ATTRIBUTE_RULE", String.valueOf(attribute.required()),
                String.valueOf(request.required()), missing);
    }

    @Transactional(readOnly = true)
    public ImpactPreview createAttributePreview(String categoryId, CreateAttributeRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        String attributeKey = text("Attribute key", request.key(), 80).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(attributeKey).matches()) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID", "Attribute key format is invalid.");
        }
        if (repository.attributes(category.id(), false).stream().anyMatch(value -> value.key().equals(attributeKey))) {
            throw CatalogException.conflict("CATEGORY_ATTRIBUTE_KEY_CONFLICT",
                    "This category already uses the attribute key.");
        }
        AttributeType type = required(request.dataType(), "Attribute type");
        validateConfiguration(type, request.validation(), request.options());
        if ((type == AttributeType.ENUM || type == AttributeType.MULTI_ENUM)
                && (request.options() == null || request.options().isEmpty())) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID",
                    "Enum attributes require at least one option.");
        }
        long missing = request.required() ? repository.listingCount(category.id()) : 0;
        return preview(category, "ATTRIBUTE_CREATE", "absent",
                attributeKey + "/" + type.name() + "/required=" + request.required(), missing);
    }

    @Transactional
    public CategoryDetail updateAttribute(String categoryId, String attributeId, UpdateAttributeRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = locked(categoryId);
        CatalogRepository.AttributeRow before = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        validateConfiguration(before.dataType(), request.validation(), null);
        Instant now = Instant.now();
        CatalogRepository.AttributeRow updated = new CatalogRepository.AttributeRow(before.id(), before.categoryId(),
                before.key(), text("Attribute label", request.label(), 160),
                optionalText(request.description(), 1000), before.dataType(), request.required(),
                request.searchable(), request.filterable(), json(request.validation()), order(request.displayOrder()),
                before.status(), before.version(), before.createdAt(), now);
        version(repository.updateAttribute(updated, request.expectedVersion(), now),
                "CATEGORY_ATTRIBUTE_VERSION_CONFLICT");
        repository.bumpRule(category.id(), now);
        publishCurrentRule(category.id(), reason(request.reason()), context, now);
        event(category.id(), "ATTRIBUTE_UPDATED", context, "ATTRIBUTE", before.id(), json(before), json(updated),
                reason(request.reason()), Map.of("keyImmutable", "true"), now);
        return detail(category.id(), context);
    }

    @Transactional
    public CategoryDetail changeAttributeStatus(String categoryId, String attributeId,
                                                ChangeAttributeStatusRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = locked(categoryId);
        CatalogRepository.AttributeRow before = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        AttributeStatus status = required(request.status(), "Attribute status");
        Instant now = Instant.now();
        version(repository.updateAttributeStatus(category.id(), before.id(), request.expectedVersion(), status, now),
                "CATEGORY_ATTRIBUTE_VERSION_CONFLICT");
        repository.bumpRule(category.id(), now);
        publishCurrentRule(category.id(), reason(request.reason()), context, now);
        event(category.id(), "ATTRIBUTE_" + status.name(), context, "ATTRIBUTE", before.id(), json(before),
                json(Map.of("status", status.name())), reason(request.reason()), Map.of(), now);
        return detail(category.id(), context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview attributeStatusPreview(String categoryId, String attributeId,
                                                ChangeAttributeStatusRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        CatalogRepository.AttributeRow attribute = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        AttributeStatus status = required(request.status(), "Attribute status");
        long missing = status == AttributeStatus.ACTIVE && attribute.required()
                ? repository.missingRequired(category.id(), attribute.id()) : 0;
        return preview(category, "ATTRIBUTE_STATUS", attribute.status().name(), status.name(), missing);
    }

    @Transactional
    public CategoryDetail createOption(String categoryId, String attributeId, CreateOptionRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = locked(categoryId);
        CatalogRepository.AttributeRow attribute = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        if (attribute.dataType() != AttributeType.ENUM && attribute.dataType() != AttributeType.MULTI_ENUM) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID",
                    "Only enum attributes can contain options.");
        }
        String value = text("Option value", request.value(), 120).toLowerCase(Locale.ROOT);
        if (!OPTION.matcher(value).matches()) {
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID", "Option value format is invalid.");
        }
        Instant now=Instant.now();
        try { repository.insertOption(ulidGenerator.next(), attribute.id(), value,
                text("Option label", request.label(), 160), order(request.displayOrder()), now); }
        catch (DuplicateKeyException exception) {
            throw CatalogException.conflict("CATEGORY_ATTRIBUTE_KEY_CONFLICT", "This option value already exists.");
        }
        repository.bumpRule(category.id(), now);
        publishCurrentRule(category.id(), reason(request.reason()), context, now);
        event(category.id(), "ATTRIBUTE_OPTION_CREATED", context, "ATTRIBUTE_OPTION", attribute.id(), null,
                json(Map.of("value", value)), reason(request.reason()), Map.of(), now);
        return detail(category.id(), context);
    }

    @Transactional
    public CategoryDetail changeOptionStatus(String categoryId, String attributeId, String optionId,
                                             ChangeOptionStatusRequest request) {
        AdminContext context=require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context,AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category=locked(categoryId);
        CatalogRepository.AttributeRow attribute=repository.lockAttribute(category.id(),attributeId)
                .orElseThrow(()->CatalogException.notFound("Category attribute"));
        CatalogRepository.OptionRow option=repository.lockOption(attribute.id(),optionId)
                .orElseThrow(()->CatalogException.notFound("Attribute option"));
        String status=requiredStatus(request.status());
        Instant now=Instant.now();
        version(repository.updateOptionStatus(attribute.id(),option.id(),request.expectedVersion(),status,now),
                "CATEGORY_ATTRIBUTE_VERSION_CONFLICT");
        repository.bumpRule(category.id(),now);
        publishCurrentRule(category.id(),reason(request.reason()),context,now);
        event(category.id(),"ATTRIBUTE_OPTION_"+status,context,"ATTRIBUTE_OPTION",option.id(),json(option),
                json(Map.of("status",status)),reason(request.reason()),Map.of(),now);
        return detail(category.id(),context);
    }

    @Transactional(readOnly = true)
    public ImpactPreview optionStatusPreview(String categoryId, String attributeId, String optionId,
                                             ChangeOptionStatusRequest request) {
        AdminContext context = require(AdminPermission.CATALOG_ATTRIBUTE_MANAGE);
        requireAlso(context, AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category = found(categoryId);
        CatalogRepository.AttributeRow attribute = repository.lockAttribute(category.id(), attributeId)
                .orElseThrow(() -> CatalogException.notFound("Category attribute"));
        CatalogRepository.OptionRow option = repository.lockOption(attribute.id(), optionId)
                .orElseThrow(() -> CatalogException.notFound("Attribute option"));
        String status = requiredStatus(request.status());
        return preview(category, "ATTRIBUTE_OPTION_STATUS", option.status(), status, 0);
    }

    @Transactional
    public CategoryDetail createGuidance(String categoryId, CreateGuidanceRequest request) {
        AdminContext context=require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category=locked(categoryId);
        Instant now=Instant.now(); String id=ulidGenerator.next();
        repository.insertGuidance(id,category.id(),required(request.guidanceType(),"Guidance type"),
                text("Guidance title",request.title(),180),text("Guidance body",request.body(),4000),
                order(request.displayOrder()),now);
        event(category.id(),"GUIDANCE_CREATED",context,"SELLER_GUIDANCE",id,null,json(request),
                reason(request.reason()),Map.of("enforcement","false"),now);
        return detail(category.id(),context);
    }

    @Transactional
    public CategoryDetail updateGuidance(String categoryId,String guidanceId,UpdateGuidanceRequest request) {
        AdminContext context=require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category=locked(categoryId);
        CatalogRepository.GuidanceRow before=repository.lockGuidance(category.id(),guidanceId)
                .orElseThrow(()->CatalogException.notFound("Seller guidance"));
        CatalogRepository.GuidanceRow updated=new CatalogRepository.GuidanceRow(before.id(),before.categoryId(),
                required(request.guidanceType(),"Guidance type"),text("Guidance title",request.title(),180),
                text("Guidance body",request.body(),4000),order(request.displayOrder()),before.status(),before.version());
        Instant now=Instant.now();
        version(repository.updateGuidance(updated,request.expectedVersion(),now),"CATEGORY_VERSION_CONFLICT");
        event(category.id(),"GUIDANCE_UPDATED",context,"SELLER_GUIDANCE",before.id(),json(before),json(updated),
                reason(request.reason()),Map.of("enforcement","false"),now);
        return detail(category.id(),context);
    }

    @Transactional
    public CategoryDetail changeGuidanceStatus(String categoryId,String guidanceId,
                                               ChangeGuidanceStatusRequest request) {
        AdminContext context=require(AdminPermission.CATALOG_POLICY_MANAGE);
        CatalogRepository.CategoryRow category=locked(categoryId);
        CatalogRepository.GuidanceRow before=repository.lockGuidance(category.id(),guidanceId)
                .orElseThrow(()->CatalogException.notFound("Seller guidance"));
        String status=requiredStatus(request.status()); Instant now=Instant.now();
        version(repository.updateGuidanceStatus(category.id(),before.id(),request.expectedVersion(),status,now),
                "CATEGORY_VERSION_CONFLICT");
        event(category.id(),"GUIDANCE_"+status,context,"SELLER_GUIDANCE",before.id(),json(before),
                json(Map.of("status",status)),reason(request.reason()),Map.of("enforcement","false"),now);
        return detail(category.id(),context);
    }

    private CategoryDetail detail(String id, AdminContext context) {
        CategorySummary category=repository.findSummary(id).orElseThrow(()->CatalogException.notFound("Category"));
        List<CatalogEvent> events=repository.events(id);
        Set<String> actorIds=events.stream().map(CatalogEvent::actorId).collect(Collectors.toSet());
        Map<String,String> names=actorIds.isEmpty()?Map.of():authServiceClient.lookupAdminIdentityLabels(
                context.accessToken(),actorIds,Set.of()).users().stream().collect(Collectors.toMap(
                AuthServiceClient.UserIdentityLabel::id,AuthServiceClient.UserIdentityLabel::displayName,(a,b)->a));
        List<CatalogEvent> labeled=events.stream().map(event->new CatalogEvent(event.eventId(),event.occurredAt(),
                event.eventType(),event.actorId(),names.get(event.actorId()),event.targetType(),event.targetId(),
                event.previousState(),event.newState(),event.reason(),event.correlationId(),event.requestId(),
                event.safeMetadata())).toList();
        return new CategoryDetail(category,repository.breadcrumb(id),repository.children(id),repository.attributes(id,false),
                repository.guidance(id,false),repository.rules(id),labeled,capabilities(context.authorization()));
    }

    private ImpactPreview preview(CatalogRepository.CategoryRow category,String type,String current,
                                  String proposed,long missing) {
        CategoryCounts counts=repository.findSummary(category.id()).orElseThrow().counts();
        return new ImpactPreview(category.id(),type,current,proposed,counts,missing,true,
                List.of("New listing eligibility or validation will use the proposed configuration after confirmation."),
                List.of("Existing active listings are not removed.","Pending moderation is not rewritten.",
                        "Orders are not changed.","No seller or enforcement action is created."),
                category.version(),category.ruleVersion());
    }

    private void publishCurrentRule(String categoryId,String reason,AdminContext context,Instant now) {
        CatalogRepository.CategoryRow category=repository.find(categoryId).orElseThrow();
        Map<String,Object> configuration=new LinkedHashMap<>();
        configuration.put("status",category.status().name());
        configuration.put("sellerEligibility",category.sellerEligibility().name());
        configuration.put("listingCreationAllowed",category.creationAllowed());
        configuration.put("listingSubmissionAllowed",category.submissionAllowed());
        configuration.put("attributes",repository.attributes(categoryId,false));
        String json=json(configuration);
        repository.publishRule(categoryId,ulidGenerator.next(),json,hash(json),reason,context.userId(),now);
        event(categoryId,"CATEGORY_RULE_VERSION_PUBLISHED",context,"RULE_VERSION",categoryId,null,
                json(Map.of("version",category.ruleVersion())),reason,Map.of(),now);
    }

    private void insertOptions(String attributeId,List<AttributeOptionInput> options,Instant now) {
        if(options==null)return;
        for(AttributeOptionInput option:options){String value=text("Option value",option.value(),120).toLowerCase(Locale.ROOT);
            if(!OPTION.matcher(value).matches())throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Option value format is invalid.");
            repository.insertOption(ulidGenerator.next(),attributeId,value,text("Option label",option.label(),160),
                    order(option.displayOrder()),now);}
    }

    private void validateConfiguration(AttributeType type,Map<String,Object> validation,List<AttributeOptionInput> options) {
        Map<String,Object> rules=validation==null?Map.of():validation;
        Set<String> allowed=switch(type){case TEXT->Set.of("minLength","maxLength");case NUMBER->Set.of("minimum","maximum","integerOnly");default->Set.of();};
        if(!allowed.containsAll(rules.keySet()))throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Validation contains unsupported rules.");
        if((type==AttributeType.ENUM||type==AttributeType.MULTI_ENUM)&&options!=null&&options.isEmpty())
            throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Enum attributes require at least one option.");
    }

    private void requireParent(String categoryId,String parentId){if(parentId==null)return;
        found(parentId); if(parentId.equals(categoryId))throw CatalogException.invalid("CATEGORY_PARENT_INVALID","A category cannot be its own parent.");
        if(categoryId!=null&&repository.parentWouldCycle(categoryId,parentId))throw CatalogException.conflict("CATEGORY_HIERARCHY_CYCLE","The requested parent would create a hierarchy cycle.");
        if(repository.depthFrom(categoryId,parentId)>MAX_DEPTH)throw CatalogException.invalid("CATEGORY_PARENT_INVALID","Category hierarchy cannot exceed three levels.");}

    private CategoryDetail replay(CatalogRepository.IdempotencyRow replay,String hash,AdminContext context){
        if(!replay.requestHash().equals(hash))throw CatalogException.conflict("CATALOG_IDEMPOTENCY_CONFLICT","Idempotency key was reused with different content.");
        return detail(replay.targetId(),context);}
    private CatalogRepository.CategoryRow found(String id){return repository.find(categoryId(id)).orElseThrow(()->CatalogException.notFound("Category"));}
    private CatalogRepository.CategoryRow locked(String id){return repository.lock(categoryId(id)).orElseThrow(()->CatalogException.notFound("Category"));}
    private void version(int count,String code){if(count==0)throw CatalogException.conflict(code,"Catalog configuration changed in another request. Reload before continuing.");}
    private Capabilities capabilities(AuthServiceClient.PlatformAdminAuthorization a){boolean category=a.hasPermission(AdminPermission.CATALOG_CATEGORY_MANAGE),attribute=a.hasPermission(AdminPermission.CATALOG_ATTRIBUTE_MANAGE),policy=a.hasPermission(AdminPermission.CATALOG_POLICY_MANAGE);return new Capabilities(true,category,attribute,policy,policy,category&&policy,policy,!category&&!attribute&&!policy,!category&&!attribute&&!policy?"Your role can inspect catalog configuration but cannot change it.":null);}
    private AdminContext require(AdminPermission permission){CurrentActor actor=currentActorProvider.currentActor();AuthServiceClient.PlatformAdminAuthorization admin=authServiceClient.requirePlatformAdmin(actor.accessToken());if(!admin.hasPermission(permission))throw new com.msb.ecom.product_service.model.ListingAuthorizationException("Missing required permission: "+permission.id());return new AdminContext(actor.accessToken(),admin.userId(),admin);}
    private void requireAlso(AdminContext context,AdminPermission permission){if(!context.authorization().hasPermission(permission))throw new com.msb.ecom.product_service.model.ListingAuthorizationException("Missing required permission: "+permission.id());}
    private void event(String categoryId,String type,AdminContext context,String targetType,String targetId,String before,String after,String reason,Map<String,String> metadata,Instant now){String correlationId=correlationId();repository.event(ulidGenerator.next(),categoryId,type,context.userId(),targetType,targetId,before,after,reason,correlationId,MDC.get("requestId"),json(metadata),now);log.info("Catalog change eventType={} categoryId={} targetId={} adminUserId={} correlationId={}",type,categoryId,targetId,context.userId(),correlationId);}
    private String correlationId(){String value=MDC.get("correlationId");return value==null||value.isBlank()?"catalog-"+ulidGenerator.next():value;}
    private String categoryId(String value){return text("Category ID",value,26);}
    private String optionalId(String value){return value==null||value.isBlank()?null:categoryId(value);}
    private String idempotencyKey(String value){if(value==null||!IDEMPOTENCY.matcher(value).matches())throw CatalogException.invalid("CATALOG_IDEMPOTENCY_CONFLICT","A valid Idempotency-Key header is required.");return value;}
    private String slug(String value){String normalized=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");if(normalized.isBlank())throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Category name cannot produce an empty slug.");return normalized;}
    private int order(Integer value){int result=value==null?0:value;if(result<0||result>100000)throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Display order is outside the supported range.");return result;}
    private String text(String label,String value,int max){if(value==null||value.trim().isEmpty()||value.trim().length()>max)throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID",label+" is required and must be at most "+max+" characters.");return value.trim();}
    private String optionalText(String value,int max){if(value==null||value.isBlank())return null;if(value.trim().length()>max)throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Text is too long.");return value.trim();}
    private String reason(String value){return text("Reason",value,1000);}
    private String requiredStatus(String value){if(!"ACTIVE".equals(value)&&!"DISABLED".equals(value))throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID","Status must be ACTIVE or DISABLED.");return value;}
    private <T>T required(T value,String label){if(value==null)throw CatalogException.invalid("CATEGORY_ATTRIBUTE_VALUE_INVALID",label+" is required.");return value;}
    private String json(Object value){try{return objectMapper.writeValueAsString(value==null?Map.of():value);}catch(JsonProcessingException exception){throw new IllegalStateException("Catalog state could not be serialized.",exception);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}
    private record AdminContext(String accessToken,String userId,AuthServiceClient.PlatformAdminAuthorization authorization) { }
}
