export type ListingSellerType = 'INDIVIDUAL' | 'BUSINESS';
export type ListingCondition = 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS';
export type ListingMediaUploadStatus = 'PENDING_UPLOAD' | 'UPLOADED' | 'FAILED';
export type ListingMediaModerationStatus = 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';
export type ListingPublicationSource = 'ADMIN_REVIEW' | 'BUSINESS_SELF_PUBLISHED';
export type ListingModerationDecision = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES';
export type ListingModerationHistoryDecision = ListingModerationDecision | 'ADMIN_EDIT' | 'ADMIN_REMOVE';
export type ListingModerationCaseFilter = 'open' | 'unassigned' | 'assigned_to_me' | 'resolved';
export type ListingModerationCaseStatus = 'OPEN' | 'CLAIMED' | 'RESOLVED';
export type ListingModerationCasePriority = 'LOW' | 'NORMAL' | 'HIGH';

export interface Category {
  id: string;
  slug: string;
  name: string;
  parentId: string | null;
  displayOrder: number;
  status?: string;
  sellerEligibility?: 'INDIVIDUAL'|'BUSINESS'|'BOTH'|'NONE';
  listingCreationAllowed?: boolean;
  listingSubmissionAllowed?: boolean;
  ruleVersion?: number;
  attributes: CategoryAttribute[];
  guidance?: CategorySellerGuidance[];
}

export interface CategoryAttributeOption { id:string;value:string;label:string;displayOrder:number;status:string;version:number; }
export interface CategorySellerGuidance { id:string;guidanceType:string;title:string;body:string;displayOrder:number;status:string;version:number; }

export interface CategoryAttribute {
  id: string;
  key: string;
  label: string;
  description?: string|null;
  dataType: string;
  required: boolean;
  searchable?:boolean;
  filterable?:boolean;
  allowedValuesJson: string | null;
  validationJson: string | null;
  displayOrder: number;
  status?:string;
  version?:number;
  options?:CategoryAttributeOption[];
}

export interface CreateListingDraftRequest {
  sellerType: ListingSellerType;
  businessId?: string | null;
  categoryId: string;
  title: string;
  description: string;
  condition: ListingCondition;
  conditionNotes?: string | null;
  price: {
    amount: number;
    currency: string;
  };
  negotiable?: boolean;
  location?: {
    city?: string | null;
    region?: string | null;
  } | null;
  sku?: string | null;
  quantity?: number | null;
  categoryRuleVersion?:number|null;
  attributes?:Record<string,unknown>;
}

export interface ListingCatalogValues { listingId:string;categoryId:string;categoryRuleVersion:number;attributes:Record<string,unknown>; }

export interface AdminActiveListingUpdateRequest {
  categoryId: string;
  title: string;
  description: string;
  condition: ListingCondition;
  conditionNotes?: string | null;
  price: {
    amount: number;
    currency: string;
  };
  negotiable?: boolean;
  location?: {
    city?: string | null;
    region?: string | null;
  } | null;
  sku?: string | null;
  quantity?: number | null;
  reason: string;
}

export interface AdminListingRemoveRequest {
  reason: string;
}

export interface ListingDraft {
  id: string;
  sellerType: ListingSellerType;
  individualSellerUserId: string | null;
  businessId: string | null;
  storeId?: string | null;
  sellerDisplayName?: string | null;
  storeSlug?: string | null;
  storeName?: string | null;
  businessVerified?: boolean;
  categoryId: string;
  title: string;
  description: string;
  condition: ListingCondition;
  conditionNotes: string | null;
  priceAmount: number;
  currency: string;
  negotiable: boolean;
  sku: string | null;
  quantity: number;
  publicCity: string | null;
  publicRegion: string | null;
  status: string;
  moderationStatus: string;
  publicationSource: ListingPublicationSource | null;
  publishedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  moderationAction?: string | null;
  moderationReason?: string | null;
  moderationActionAt?: string | null;
  images?: ListingImage[];
}

export type BusinessStoreItemManagementStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'REMOVED_BY_ADMIN';

export interface BusinessStoreItemSearchParams {
  q?: string | null;
  status?: BusinessStoreItemManagementStatus | null;
  cursor?: string | null;
  limit?: number | null;
}

export interface BusinessStoreItemStatusSummary {
  total: number;
  draft: number;
  active: number;
  paused: number;
  removed: number;
}

export interface BusinessStoreItemSearchPage {
  data: ListingDraft[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
  summary: BusinessStoreItemStatusSummary;
}

export interface ListingMediaUploadRequest {
  contentType: string;
  fileName?: string | null;
  sizeBytes: number;
  checksumSha256?: string | null;
}

export interface ListingMediaConfirmRequest {
  sizeBytes: number;
  checksumSha256?: string | null;
}

export interface ListingMedia {
  id: string;
  listingId: string;
  sellerType: ListingSellerType;
  individualSellerUserId: string | null;
  businessId: string | null;
  objectBucket: string;
  objectKey: string;
  originalFileName: string | null;
  contentType: string;
  sizeBytes: number;
  checksumSha256: string | null;
  uploadStatus: ListingMediaUploadStatus;
  moderationStatus: ListingMediaModerationStatus;
  uploadMethod: string;
  uploadUrl: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ListingImageRequest {
  mediaId: string;
  altText?: string | null;
}

export interface UpdateListingImagesRequest {
  images: ListingImageRequest[];
}

export interface ListingImage {
  id: string;
  listingId: string;
  mediaObjectId: string;
  displayOrder: number;
  altText: string | null;
  moderationStatus: ListingMediaModerationStatus;
  originalFileName: string | null;
  contentType: string;
  sizeBytes: number;
  uploadStatus: ListingMediaUploadStatus;
  objectBucket: string;
  objectKey: string;
  uploadUrl: string;
  url?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ListingModerationDecisionRequest {
  decision: ListingModerationDecision;
  reason: string;
}

export interface ListingModerationDecisionResponse {
  id: string;
  listingId: string;
  decision: ListingModerationHistoryDecision;
  reason: string;
  reviewerUserId: string;
  listingVersion: number;
  createdAt: string;
}

export interface PublicListingImage {
  id: string;
  displayOrder: number;
  altText: string | null;
  originalFileName: string | null;
  contentType: string;
  sizeBytes: number;
  uploadUrl: string;
  url?: string;
}

export interface PublicListing {
  id: string;
  sellerType: ListingSellerType;
  sellerDisplayName?: string | null;
  sellerAvatarUrl?: string | null;
  storeId?: string | null;
  storeSlug?: string | null;
  storeName?: string | null;
  businessVerified?: boolean;
  categoryId: string;
  categorySlug: string;
  categoryName: string;
  title: string;
  description: string;
  condition: ListingCondition;
  conditionNotes: string | null;
  priceAmount: number;
  currency: string;
  negotiable: boolean;
  quantity: number;
  publicCity: string | null;
  publicRegion: string | null;
  publishedAt: string;
  transactionNotice: string | null;
  visitCount: number;
  likeCount: number;
  images: PublicListingImage[];
}

export interface ListingEngagement {
  listingId: string;
  visitCount: number;
  likeCount: number;
  visitedByMe: boolean;
  likedByMe: boolean;
}

export type PublicListingSort = 'none' | 'newest' | 'price_asc' | 'price_desc';

export interface PublicListingSearchParams {
  q?: string | null;
  categoryId?: string | null;
  condition?: ListingCondition | null;
  minPrice?: number | null;
  maxPrice?: number | null;
  city?: string | null;
  county?: string | null;
  sort?: PublicListingSort | null;
  cursor?: string | null;
  limit?: number | null;
}

export interface PublicListingSearchPage {
  data: PublicListing[];
  page: {
    nextCursor: string | null;
    hasMore: boolean;
  };
}

export type MarketplaceListingSort = PublicListingSort;
export type MarketplaceListingSearchParams = PublicListingSearchParams;
export type MarketplaceListingSearchPage = PublicListingSearchPage;
export type MarketplaceBrowseListing = PublicListing;
export type BusinessStoreListing = PublicListing;
export type BusinessStoreListingSort = PublicListingSort;
export type BusinessStoreListingSearchParams = PublicListingSearchParams;
export type BusinessStoreListingSearchPage = PublicListingSearchPage;

export interface AdminListingModerationCase {
  id: string;
  caseStatus: ListingModerationCaseStatus;
  priority: ListingModerationCasePriority;
  assignedAdminUserId: string | null;
  assignedAdminDisplayName?: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  submittedByUserId: string;
  sellerId?: string | null;
  sellerDisplayName?: string | null;
  listingId: string;
  title: string;
  sellerType: ListingSellerType;
  listingStatus: string;
  listingModerationStatus: string;
  priceAmount: number;
  currency: string;
  publicCity: string | null;
  publicRegion: string | null;
  sku: string | null;
  quantity: number;
}

export interface AdminListingModerationCaseDetail {
  moderationCase: AdminListingModerationCase;
  listing: ListingDraft;
  decisions: ListingModerationDecisionResponse[];
}

export type ListingEnforcementActionType = 'RESTRICT' | 'SUSPEND';
export type ListingEnforcementScope = 'LISTING_PUBLIC_VISIBILITY' | 'LISTING_PURCHASABILITY';

export interface ListingEffectiveRestriction {
  scope: ListingEnforcementScope;
  actionType: ListingEnforcementActionType;
  enforcementActionId: string;
}

export interface ListingEnforcementAction {
  enforcementActionId: string;
  actionType: ListingEnforcementActionType;
  scopes: ListingEnforcementScope[];
  lifecycleState: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  effectiveAt: string;
  expiresAt: string | null;
  version: number;
  createdAt: string;
  revokedAt: string | null;
  reasonCode: string;
  reason: string;
  actorId: string;
  actorDisplayName: string;
  caseId: string | null;
}

export interface AdminListingEnforcementDetail {
  listingId: string;
  listingStatus: string;
  moderationStatus: string;
  listingVersion: number;
  publicVisibilityAllowed: boolean;
  purchasabilityAllowed: boolean;
  strongestActiveAction: ListingEnforcementActionType | null;
  effectiveRestrictions: ListingEffectiveRestriction[];
  activeEnforcementActions: ListingEnforcementAction[];
  historicalEnforcementActions: ListingEnforcementAction[];
  availableAdminCapabilities: {
    canRead: boolean;
    canSuspend: boolean;
    canReinstate: boolean;
    removedByAdmin: boolean;
    readOnlyReason: string | null;
    operationalScopes: ListingEnforcementScope[];
  };
}

export interface ListingEnforcementCreateRequest {
  actionType: ListingEnforcementActionType;
  scopes: ListingEnforcementScope[];
  reasonCode: string;
  reason: string;
  effectiveAt: string | null;
  expiresAt: string | null;
  expectedListingVersion: number;
  idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface ListingEnforcementRevokeRequest {
  expectedEnforcementVersion: number;
  reasonCode: string;
  reason: string;
  idempotencyKey: string | null;
  safeMetadata: Record<string, string>;
}

export interface ListingEnforcementResult {
  enforcementActionId: string | null;
  targetId: string;
  actionType: ListingEnforcementActionType;
  scopes: ListingEnforcementScope[];
  lifecycleState: string;
  effectiveRestrictions: ListingEffectiveRestriction[];
  dryRun: boolean;
}

export interface ListingEnforcementPreview {
  proposedAction: ListingEnforcementResult;
  overlappingActions: ListingEnforcementAction[];
  effectiveRestrictionsAfter: ListingEffectiveRestriction[];
  predictedPublicVisibility: boolean;
  predictedPurchasability: boolean;
  targetVersionCurrent: boolean;
  impactSummary: string[];
  warnings: string[];
}

export interface ListingEnforcementTimelineEntry {
  eventId: string;
  occurredAt: string;
  eventType: string;
  actorId: string | null;
  actorDisplayName: string | null;
  enforcementActionId: string | null;
  actionType: ListingEnforcementActionType | null;
  scopes: ListingEnforcementScope[];
  reasonCode: string | null;
  reason: string | null;
  previousState: string | null;
  newState: string | null;
}
