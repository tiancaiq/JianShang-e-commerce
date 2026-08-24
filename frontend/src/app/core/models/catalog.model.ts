export type CategoryStatus = 'ACTIVE'|'DISABLED'|'DEPRECATED';
export type SellerEligibility = 'INDIVIDUAL'|'BUSINESS'|'BOTH'|'NONE';
export type CatalogAttributeType = 'TEXT'|'NUMBER'|'BOOLEAN'|'ENUM'|'MULTI_ENUM';
export type CatalogAttributeStatus = 'ACTIVE'|'DISABLED'|'DEPRECATED';
export type GuidanceType = 'TIP'|'WARNING'|'BEST_PRACTICE'|'POLICY_NOTICE';

export interface CatalogCounts { activeListings:number;draftListings:number;pendingListings:number;historicalListings:number;childCategories:number; }
export interface CatalogCategory { id:string;parentId:string|null;name:string;slug:string;description:string|null;status:CategoryStatus;displayOrder:number;sellerEligibility:SellerEligibility;listingCreationAllowed:boolean;listingSubmissionAllowed:boolean;replacementCategoryId:string|null;ruleVersion:number;version:number;counts:CatalogCounts; }
export interface CatalogOverview { categories:CatalogCategory[];activeCategories:number;disabledCategories:number;deprecatedCategories:number;activeAttributes:number;activeGuidance:number; }
export interface CatalogOption { id:string;value:string;label:string;displayOrder:number;status:string;version:number; }
export interface CatalogAttribute { id:string;key:string;label:string;description:string|null;dataType:CatalogAttributeType;required:boolean;searchable:boolean;filterable:boolean;allowedValuesJson:string|null;validationJson:string|null;displayOrder:number;status:CatalogAttributeStatus;version:number;options:CatalogOption[]; }
export interface SellerGuidance { id:string;guidanceType:GuidanceType;title:string;body:string;displayOrder:number;status:string;version:number; }
export interface CategoryPath { id:string;name:string;slug:string; }
export interface RuleVersion { id:string;versionNumber:number;reason:string;createdByAdminId:string;createdAt:string; }
export interface CatalogEvent { eventId:string;occurredAt:string;eventType:string;actorId:string;actorDisplayName:string|null;targetType:string;targetId:string;previousState:string|null;newState:string|null;reason:string;correlationId:string|null;requestId:string|null;safeMetadata:Record<string,string>; }
export interface CatalogCapabilities { canRead:boolean;canManageCategory:boolean;canManageAttribute:boolean;canManagePolicy:boolean;canManageGuidance:boolean;canDisableCategory:boolean;canPublishRules:boolean;readOnly:boolean;readOnlyReason:string|null; }
export interface CatalogDetail { category:CatalogCategory;breadcrumb:CategoryPath[];children:CatalogCategory[];attributes:CatalogAttribute[];sellerGuidance:SellerGuidance[];ruleVersions:RuleVersion[];auditTimeline:CatalogEvent[];availableAdminCapabilities:CatalogCapabilities; }
export interface CatalogImpact { categoryId:string;changeType:string;currentValue:string;proposedValue:string;counts:CatalogCounts;listingsMissingRequiredAttribute:number;exact:boolean;effects:string[];guaranteedNonEffects:string[];categoryVersion:number;ruleVersion:number; }
export interface CatalogApproval { outcome:string;approval:{approvalId:string;actionType:string;riskLevel:string;targetType:string;targetId:string;requiredApprovals:number;currentApprovals:number;status:string;createdAt:string;expiresAt:string;version:number};approvalRequired:true;message:string; }
export interface ListingCatalogValues { listingId:string;categoryId:string;categoryRuleVersion:number;attributes:Record<string,unknown>; }
