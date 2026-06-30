import {
  CreateListingDraftRequest,
  ListingCondition,
  ListingDraft,
  ListingImage,
  ListingImageRequest,
  ListingMedia,
  ListingSellerType,
} from '../../core/models/listing.model';

export const MAX_LISTING_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
export const MAX_LISTING_IMAGE_COUNT = 10;
export const ALLOWED_LISTING_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export interface ListingDraftFormState {
  sellerType: ListingSellerType;
  businessId: string;
  categoryId: string;
  title: string;
  description: string;
  condition: ListingCondition;
  conditionNotes: string;
  price: number | null;
  currency: string;
  publicCity: string;
  publicRegion: string;
  negotiable: boolean;
  sku: string;
  quantity: number | null;
}

export interface ListingDraftValidationResult {
  valid: boolean;
  message: string;
}

// Validates draft form rules before the component sends a create/update request.
export function validateListingDraftForm(state: ListingDraftFormState): ListingDraftValidationResult {
  if (!state.categoryId) {
    return invalid('Category is required.');
  }
  if (!state.title.trim()) {
    return invalid('Title is required.');
  }
  if (!state.description.trim()) {
    return invalid('Description is required.');
  }
  if (state.price === null || Number(state.price) < 0) {
    return invalid('Price must be zero or greater.');
  }
  if (!/^[A-Za-z]{3}$/.test(state.currency.trim())) {
    return invalid('Currency must be a 3-letter code.');
  }
  if (state.sellerType === 'BUSINESS') {
    if (state.businessId.trim().length !== 26) {
      return invalid('Business ID is required.');
    }
    if (!state.sku.trim()) {
      return invalid('SKU is required for business listings.');
    }
    if (state.quantity === null || Number(state.quantity) < 1) {
      return invalid('Quantity must be at least 1.');
    }
  } else if (state.quantity === null || Number(state.quantity) < 1) {
    return invalid('Quantity must be at least 1.');
  }

  return { valid: true, message: '' };
}

// Builds the backend draft request while preserving individual-vs-business listing rules.
export function buildListingDraftRequest(state: ListingDraftFormState): CreateListingDraftRequest {
  return {
    sellerType: state.sellerType,
    businessId: state.sellerType === 'BUSINESS' ? state.businessId.trim() : null,
    categoryId: state.categoryId,
    title: state.title.trim(),
    description: state.description.trim(),
    condition: state.condition,
    conditionNotes: state.conditionNotes.trim() || null,
    price: {
      amount: Number(state.price),
      currency: state.currency.trim().toUpperCase(),
    },
    negotiable: state.sellerType === 'INDIVIDUAL' ? state.negotiable : false,
    location: state.sellerType === 'INDIVIDUAL'
      ? { city: state.publicCity.trim() || null, region: state.publicRegion.trim() || null }
      : null,
    sku: state.sellerType === 'BUSINESS' ? state.sku.trim() : null,
    quantity: Number(state.quantity),
  };
}

// Converts an API draft into the editable form state shown by the component.
export function listingDraftToFormState(listing: ListingDraft): ListingDraftFormState {
  return {
    sellerType: listing.sellerType,
    businessId: listing.businessId || '',
    categoryId: listing.categoryId,
    title: listing.title,
    description: listing.description,
    condition: listing.condition,
    conditionNotes: listing.conditionNotes || '',
    price: Number(listing.priceAmount),
    currency: listing.currency,
    publicCity: listing.publicCity || '',
    publicRegion: listing.publicRegion || '',
    negotiable: listing.negotiable,
    sku: listing.sku || '',
    quantity: listing.quantity,
  };
}

// Validates selected listing images before any storage request is made.
export function validateSelectedListingImage(file: File): string {
  if (!ALLOWED_LISTING_IMAGE_TYPES.has(file.type)) {
    return 'Use a JPEG, PNG, or WebP image.';
  }
  if (file.size <= 0 || file.size > MAX_LISTING_IMAGE_SIZE_BYTES) {
    return 'Image must be 10 MB or less.';
  }
  return '';
}

// Appends confirmed media to the listing image request while preserving existing order.
export function appendConfirmedMediaToImages(currentImages: ListingImage[], media: ListingMedia): ListingImageRequest[] {
  return [
    ...currentImages.map(item => ({
      mediaId: item.mediaObjectId,
      altText: item.altText,
    })),
    {
      mediaId: media.id,
      altText: media.originalFileName,
    },
  ];
}

function invalid(message: string): ListingDraftValidationResult {
  return { valid: false, message };
}
