import { ListingDraft, ListingImage, ListingMedia } from '../../core/models/listing.model';
import {
  appendConfirmedMediaToImages,
  buildListingDraftRequest,
  canSubmitListingForReview,
  isClosableListingStatus,
  isEditableListingStatus,
  listingDraftToFormState,
  listingDraftRequestSnapshot,
  validateListingDraftForm,
  validateSelectedListingImage,
} from './listing-draft-form.helpers';

describe('listing draft form helpers', () => {
  const baseState = {
    sellerType: 'INDIVIDUAL' as const,
    businessId: '',
    categoryId: '01K00000000000000000000001',
    title: ' Used bicycle ',
    description: ' A reliable city bike. ',
    condition: 'GOOD' as const,
    conditionNotes: ' Light scratches ',
    price: 250,
    currency: 'usd',
    publicCity: ' Irvine ',
    publicRegion: ' CA ',
    negotiable: true,
    sku: '',
    quantity: 2,
  };

  it('builds individual draft requests with trimmed public location and seller-entered quantity', () => {
    expect(buildListingDraftRequest(baseState)).toEqual(jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      businessId: null,
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      conditionNotes: 'Light scratches',
      price: { amount: 250, currency: 'USD' },
      negotiable: true,
      location: { city: 'Irvine', region: 'CA' },
      sku: null,
      quantity: 2,
    }));
  });

  it('creates a stable draft request snapshot for dirty-state checks', () => {
    expect(listingDraftRequestSnapshot(baseState))
      .toBe(JSON.stringify(buildListingDraftRequest(baseState)));
  });

  it('builds business draft requests with business-only fields', () => {
    const request = buildListingDraftRequest({
      ...baseState,
      sellerType: 'BUSINESS',
      businessId: '01B00000000000000000000001',
      sku: ' SKU-100 ',
      quantity: 3,
    });

    expect(request).toEqual(jasmine.objectContaining({
      sellerType: 'BUSINESS',
      businessId: '01B00000000000000000000001',
      negotiable: false,
      location: null,
      sku: 'SKU-100',
      quantity: 3,
    }));
  });

  it('validates required listing draft fields', () => {
    expect(validateListingDraftForm({ ...baseState, title: '' })).toEqual({
      valid: false,
      message: 'Title is required.',
    });
    expect(validateListingDraftForm({ ...baseState, currency: 'US' })).toEqual({
      valid: false,
      message: 'Currency must be a 3-letter code.',
    });
    expect(validateListingDraftForm({
      ...baseState,
      sellerType: 'BUSINESS',
      businessId: '01B00000000000000000000001',
      sku: '',
      quantity: 3,
    })).toEqual({
      valid: false,
      message: 'SKU is required for business listings.',
    });
  });

  it('converts an API draft into editable form state', () => {
    const draft: ListingDraft = {
      id: '01L00000000000000000000001',
      sellerType: 'INDIVIDUAL',
      individualSellerUserId: '01U00000000000000000000001',
      businessId: null,
      categoryId: baseState.categoryId,
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      condition: 'GOOD',
      conditionNotes: null,
      priceAmount: 250,
      currency: 'USD',
      negotiable: true,
      sku: null,
      quantity: 1,
      publicCity: 'Irvine',
      publicRegion: 'CA',
      status: 'DRAFT',
      moderationStatus: 'NOT_SUBMITTED',
      version: 0,
      createdAt: '2026-06-16T12:00:00Z',
      updatedAt: '2026-06-16T12:00:00Z',
    };

    expect(listingDraftToFormState(draft)).toEqual(jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      title: 'Used bicycle',
      conditionNotes: '',
      price: 250,
      publicCity: 'Irvine',
      sku: '',
      quantity: 1,
    }));
  });

  it('validates selected listing images before upload', () => {
    expect(validateSelectedListingImage(new File(['x'], 'bike.gif', { type: 'image/gif' })))
      .toBe('Use a JPEG, PNG, or WebP image.');
    expect(validateSelectedListingImage(new File(['x'], 'bike.png', { type: 'image/png' })))
      .toBe('');
  });

  it('appends confirmed media to existing listing image requests', () => {
    const existingImage = {
      mediaObjectId: '01M00000000000000000000001',
      altText: 'old.png',
    } as ListingImage;
    const media = {
      id: '01M00000000000000000000002',
      originalFileName: 'new.png',
    } as ListingMedia;

    expect(appendConfirmedMediaToImages([existingImage], media)).toEqual([
      { mediaId: '01M00000000000000000000001', altText: 'old.png' },
      { mediaId: '01M00000000000000000000002', altText: 'new.png' },
    ]);
  });

  it('identifies statuses that seller edit actions can still update', () => {
    expect(isEditableListingStatus('DRAFT')).toBeTrue();
    expect(isEditableListingStatus('PENDING_REVIEW')).toBeTrue();
    expect(isEditableListingStatus('ACTIVE')).toBeTrue();
    expect(isEditableListingStatus('CLOSED')).toBeTrue();
    expect(isEditableListingStatus('SOLD')).toBeFalse();
  });

  it('identifies statuses that seller close actions can still close', () => {
    expect(isClosableListingStatus('DRAFT')).toBeTrue();
    expect(isClosableListingStatus('PENDING_REVIEW')).toBeTrue();
    expect(isClosableListingStatus('ACTIVE')).toBeTrue();
    expect(isClosableListingStatus('CLOSED')).toBeFalse();
  });

  it('allows review submit only when image and status rules are satisfied', () => {
    expect(canSubmitListingForReview({
      editMode: true,
      status: 'DRAFT',
      attachedImageCount: 1,
      pendingImageCount: 0,
      hasUnsavedChanges: false,
    })).toBeTrue();
    expect(canSubmitListingForReview({
      editMode: true,
      status: 'PENDING_REVIEW',
      attachedImageCount: 1,
      pendingImageCount: 0,
      hasUnsavedChanges: false,
    })).toBeFalse();
    expect(canSubmitListingForReview({
      editMode: true,
      status: 'ACTIVE',
      attachedImageCount: 1,
      pendingImageCount: 0,
      hasUnsavedChanges: true,
    })).toBeTrue();
    expect(canSubmitListingForReview({
      editMode: true,
      status: 'CLOSED',
      attachedImageCount: 1,
      pendingImageCount: 0,
      hasUnsavedChanges: true,
    })).toBeTrue();
    expect(canSubmitListingForReview({
      editMode: true,
      status: 'DRAFT',
      attachedImageCount: 1,
      pendingImageCount: 1,
      hasUnsavedChanges: true,
    })).toBeFalse();
  });
});
