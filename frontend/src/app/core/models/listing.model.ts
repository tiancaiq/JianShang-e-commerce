export type ListingSellerType = 'INDIVIDUAL' | 'BUSINESS';
export type ListingCondition = 'NEW' | 'OPEN_BOX' | 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'FOR_PARTS';

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
}
