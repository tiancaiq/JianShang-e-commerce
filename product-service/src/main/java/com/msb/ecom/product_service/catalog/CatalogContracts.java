package com.msb.ecom.product_service.catalog;

import com.msb.ecom.product_service.dto.CategoryAttributeResponse;
import com.msb.ecom.product_service.dto.CategorySellerGuidanceResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogContracts {
    private CatalogContracts() { }

    public enum CategoryStatus { ACTIVE, DISABLED, DEPRECATED }
    public enum SellerEligibility { INDIVIDUAL, BUSINESS, BOTH, NONE }
    public enum AttributeType { TEXT, NUMBER, BOOLEAN, ENUM, MULTI_ENUM }
    public enum AttributeStatus { ACTIVE, DISABLED, DEPRECATED }
    public enum GuidanceType { TIP, WARNING, BEST_PRACTICE, POLICY_NOTICE }

    public record CategoryCounts(long activeListings, long draftListings, long pendingListings,
                                 long historicalListings, long childCategories) { }
    public record CategorySummary(String id, String parentId, String name, String slug,
                                  String description, CategoryStatus status, int displayOrder,
                                  SellerEligibility sellerEligibility, boolean listingCreationAllowed,
                                  boolean listingSubmissionAllowed, String replacementCategoryId,
                                  long ruleVersion, long version, CategoryCounts counts) { }
    public record CategoryPath(String id, String name, String slug) { }
    public record RuleVersion(String id, long versionNumber, String reason,
                              String createdByAdminId, Instant createdAt) { }
    public record CatalogEvent(String eventId, Instant occurredAt, String eventType,
                               String actorId, String actorDisplayName, String targetType,
                               String targetId, String previousState, String newState,
                               String reason, String correlationId, String requestId,
                               Map<String, String> safeMetadata) { }
    public record Capabilities(boolean canRead, boolean canManageCategory,
                               boolean canManageAttribute, boolean canManagePolicy,
                               boolean canManageGuidance, boolean canDisableCategory,
                               boolean canPublishRules, boolean readOnly, String readOnlyReason) { }
    public record CategoryDetail(CategorySummary category, List<CategoryPath> breadcrumb,
                                 List<CategorySummary> children, List<CategoryAttributeResponse> attributes,
                                 List<CategorySellerGuidanceResponse> sellerGuidance,
                                 List<RuleVersion> ruleVersions, List<CatalogEvent> auditTimeline,
                                 Capabilities availableAdminCapabilities) { }
    public record CatalogOverview(List<CategorySummary> categories, long activeCategories,
                                  long disabledCategories, long deprecatedCategories,
                                  long activeAttributes, long activeGuidance) { }

    public record CreateCategoryRequest(String name, String parentId, String description,
                                        Integer displayOrder, SellerEligibility sellerEligibility,
                                        CategoryStatus status) { }
    public record UpdateCategoryRequest(long expectedVersion, String name, String description,
                                        Integer displayOrder, SellerEligibility sellerEligibility,
                                        String reason) { }
    public record MoveCategoryRequest(long expectedVersion, String parentId, String reason) { }
    public record ChangeCategoryStatusRequest(long expectedVersion, CategoryStatus status, String reason) { }
    public record PublishPolicyRequest(long expectedVersion, SellerEligibility sellerEligibility,
                                       Boolean listingCreationAllowed, Boolean listingSubmissionAllowed,
                                       String replacementCategoryId, String reason) { }

    public record AttributeOptionInput(String value, String label, Integer displayOrder) { }
    public record CreateAttributeRequest(String key, String label, String description,
                                         AttributeType dataType, boolean required, boolean searchable,
                                         boolean filterable, Integer displayOrder,
                                         Map<String, Object> validation, List<AttributeOptionInput> options,
                                         String reason) { }
    public record UpdateAttributeRequest(long expectedVersion, String label, String description,
                                         boolean required, boolean searchable, boolean filterable,
                                         Integer displayOrder, Map<String, Object> validation, String reason) { }
    public record ChangeAttributeStatusRequest(long expectedVersion, AttributeStatus status, String reason) { }
    public record CreateOptionRequest(String value, String label, Integer displayOrder, String reason) { }
    public record ChangeOptionStatusRequest(long expectedVersion, String status, String reason) { }

    public record CreateGuidanceRequest(GuidanceType guidanceType, String title, String body,
                                        Integer displayOrder, String reason) { }
    public record UpdateGuidanceRequest(long expectedVersion, GuidanceType guidanceType,
                                        String title, String body, Integer displayOrder, String reason) { }
    public record ChangeGuidanceStatusRequest(long expectedVersion, String status, String reason) { }

    public record ImpactPreview(String categoryId, String changeType, String currentValue,
                                String proposedValue, CategoryCounts counts,
                                long listingsMissingRequiredAttribute, boolean exact,
                                List<String> effects, List<String> guaranteedNonEffects,
                                long categoryVersion, long ruleVersion) { }
    public record ApprovalReference(String approvalId, String actionType, String riskLevel,
                                    String targetType, String targetId, int requiredApprovals,
                                    int currentApprovals, String status, Instant createdAt,
                                    Instant expiresAt, long version) { }
    public record CatalogApproval(String outcome, ApprovalReference approval,
                                  boolean approvalRequired, String message) { }
}
