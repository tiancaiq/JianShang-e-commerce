import { ListingCondition, PublicListing, PublicListingImage } from '../../core/models/listing.model';

export function publicListingOwnerLabel(listing: PublicListing | null | undefined): string {
  if (!listing) {
    return 'Seller';
  }
  return listing.sellerDisplayName?.trim()
    || (listing.sellerType === 'BUSINESS' ? 'Business seller' : 'Individual seller');
}

export function publicListingLocationLabel(
  listing: Pick<PublicListing, 'publicCity' | 'publicRegion'> | null | undefined,
  fallback = 'Location not set',
): string {
  if (!listing) {
    return fallback;
  }
  return [listing.publicCity, listing.publicRegion].filter(Boolean).join(', ') || fallback;
}

export function publicListingConditionLabel(condition: ListingCondition | string): string {
  return condition.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
}

export function publicListingPrimaryImageUrl(
  listing: PublicListing,
  mediaUrl: (url: string | undefined) => string,
): string {
  const image = listing.images[0];
  return publicListingImageUrl(image, mediaUrl);
}

// Public marketplace images are intentionally served through the app media
// endpoint so the bucket can stay private.
export function publicListingImageUrl(
  image: PublicListingImage | undefined,
  mediaUrl: (url: string | undefined) => string,
): string {
  return mediaUrl(image?.id ? `/api/v1/public/listing-media/${image.id}` : image?.url || image?.uploadUrl);
}
