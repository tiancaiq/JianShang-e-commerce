export type ListingSellerType = 'INDIVIDUAL' | 'BUSINESS';
export type ListingCondition = 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS';
export type ListingMediaUploadStatus = 'PENDING_UPLOAD' | 'UPLOADED' | 'FAILED';
export type ListingMediaModerationStatus = 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';
export type ListingModerationDecision = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES';

export interface Category {
  id: string;
  slug: string;
  name: string;
  parentId: string | null;
  displayOrder: number;
  attributes: CategoryAttribute[];
}

export interface CategoryAttribute {
  id: string;
  key: string;
  label: string;
  dataType: string;
  required: boolean;
  allowedValuesJson: string | null;
  validationJson: string | null;
  displayOrder: number;
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
}

export interface ListingDraft {
  id: string;
  sellerType: ListingSellerType;
  individualSellerUserId: string | null;
  businessId: string | null;
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
  version: number;
  createdAt: string;
  updatedAt: string;
  images?: ListingImage[];
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
  decision: ListingModerationDecision;
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
  images: PublicListingImage[];
}
