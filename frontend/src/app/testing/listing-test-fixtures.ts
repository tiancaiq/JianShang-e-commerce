import { ListingDraft, ListingImage, PublicListing, PublicListingImage } from '../core/models/listing.model';

export function publicListingImage(overrides: Partial<PublicListingImage> = {}): PublicListingImage {
  const index = overrides.displayOrder ?? 0;
  const id = overrides.id || `01I0000000000000000000000${index + 1}`;
  return {
    id,
    displayOrder: index,
    altText: index === 0 ? 'Blue bike' : 'Bike side view',
    originalFileName: index === 0 ? 'bike.png' : 'bike-side.png',
    contentType: 'image/png',
    sizeBytes: index === 0 ? 1024 : 2048,
    uploadUrl: `local-demo://listing-media-local/listings/01L00000000000000000000001/image-${index}.png`,
    url: `/api/v1/public/listing-media/${id}`,
    ...overrides,
  };
}

export function publicListing(overrides: Partial<PublicListing> = {}): PublicListing {
  return {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    sellerDisplayName: 'Alex Seller',
    categoryId: '01K00000000000000000000001',
    categorySlug: 'general',
    categoryName: 'General',
    title: 'Used bicycle',
    description: 'A reliable city bike.',
    condition: 'GOOD',
    conditionNotes: 'Small scratch on frame.',
    priceAmount: 250,
    currency: 'USD',
    negotiable: true,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    publishedAt: '2026-06-17T12:00:00Z',
    transactionNotice: 'Payment and delivery are arranged directly by participants.',
    visitCount: 0,
    likeCount: 0,
    images: [publicListingImage()],
    ...overrides,
  };
}

export function listingImage(overrides: Partial<ListingImage> = {}): ListingImage {
  return {
    id: '01I00000000000000000000001',
    listingId: '01L00000000000000000000001',
    mediaObjectId: '01M00000000000000000000001',
    displayOrder: 0,
    altText: 'Blue bike',
    moderationStatus: 'NOT_SUBMITTED',
    originalFileName: 'bike.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    uploadStatus: 'UPLOADED',
    objectBucket: 'listing-media-local',
    objectKey: 'listings/01L00000000000000000000001/image.png',
    uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/image.png',
    url: '/api/v1/listings/01L00000000000000000000001/media/01M00000000000000000000001/content',
    version: 0,
    createdAt: '2026-06-17T12:00:00Z',
    updatedAt: '2026-06-17T12:00:00Z',
    ...overrides,
  };
}

export function listingDraft(overrides: Partial<ListingDraft> = {}): ListingDraft {
  return {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: 'user-1',
    businessId: null,
    storeId: null,
    sellerDisplayName: 'Alex Seller',
    categoryId: '01K00000000000000000000001',
    title: 'Used bicycle',
    description: 'A reliable city bike.',
    condition: 'GOOD',
    conditionNotes: 'Small scratch on frame.',
    priceAmount: 250,
    currency: 'USD',
    negotiable: true,
    sku: null,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    status: 'DRAFT',
    moderationStatus: 'NOT_SUBMITTED',
    publicationSource: null,
    publishedAt: null,
    version: 0,
    createdAt: '2026-06-17T12:00:00Z',
    updatedAt: '2026-06-17T12:00:00Z',
    images: [],
    ...overrides,
  };
}
