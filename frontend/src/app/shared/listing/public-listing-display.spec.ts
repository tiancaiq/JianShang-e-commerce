import { publicListing } from '../../testing/listing-test-fixtures';
import {
  publicListingConditionLabel,
  publicListingImageUrl,
  publicListingLocationLabel,
  publicListingOwnerLabel,
  publicListingPrimaryImageUrl,
} from './public-listing-display';

describe('public listing display helpers', () => {
  it('formats owner labels with safe fallbacks', () => {
    expect(publicListingOwnerLabel(null)).toBe('Seller');
    expect(publicListingOwnerLabel(publicListing({
      sellerDisplayName: '  Kira  ',
    }))).toBe('Kira');
    expect(publicListingOwnerLabel(publicListing({
      sellerDisplayName: '',
      sellerType: 'BUSINESS',
    }))).toBe('Business seller');
    expect(publicListingOwnerLabel(publicListing({
      sellerDisplayName: '',
      sellerType: 'INDIVIDUAL',
    }))).toBe('Individual seller');
  });

  it('formats public location labels with caller fallback', () => {
    expect(publicListingLocationLabel(publicListing({
      publicCity: 'Irvine',
      publicRegion: 'CA',
    }))).toBe('Irvine, CA');
    expect(publicListingLocationLabel(publicListing({
      publicCity: null,
      publicRegion: null,
    }), 'Not set')).toBe('Not set');
  });

  it('formats listing conditions', () => {
    expect(publicListingConditionLabel('OPEN_BOX')).toBe('Open Box');
    expect(publicListingConditionLabel('GOOD')).toBe('Good');
  });

  it('resolves the primary public image URL', () => {
    const listing = publicListing();
    const mediaUrl = jasmine.createSpy('mediaUrl').and.callFake((url?: string) => `resolved:${url}`);

    expect(publicListingPrimaryImageUrl(listing, mediaUrl)).toBe('resolved:/api/v1/public/listing-media/01I00000000000000000000001');
    expect(mediaUrl).toHaveBeenCalledWith('/api/v1/public/listing-media/01I00000000000000000000001');
  });

  it('prefers the app media endpoint over raw storage URLs for public images', () => {
    const listing = publicListing({
      images: [{
        id: '01I00000000000000000000099',
        displayOrder: 0,
        altText: 'Stored image',
        originalFileName: 'stored.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 100,
        uploadUrl: 'https://storage.googleapis.com/jianshang/listings/raw.jpg',
        url: 'https://storage.googleapis.com/jianshang/listings/raw.jpg',
      }],
    });
    const mediaUrl = jasmine.createSpy('mediaUrl').and.callFake((url?: string) => `resolved:${url}`);

    expect(publicListingPrimaryImageUrl(listing, mediaUrl)).toBe('resolved:/api/v1/public/listing-media/01I00000000000000000000099');
    expect(publicListingImageUrl(listing.images[0], mediaUrl)).toBe('resolved:/api/v1/public/listing-media/01I00000000000000000000099');
    expect(mediaUrl).not.toHaveBeenCalledWith('https://storage.googleapis.com/jianshang/listings/raw.jpg');
  });
});
