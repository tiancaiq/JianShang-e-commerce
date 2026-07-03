import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ListingService } from './listing.service';
import {
  AdminListingModerationCase,
  AdminListingModerationCaseDetail,
  Category,
  ListingDraft,
  ListingImage,
  ListingMedia,
  PublicListing,
} from '../models/listing.model';

describe('ListingService gateway and listing API regression', () => {
  let service: ListingService;
  let httpMock: HttpTestingController;

  const categories: Category[] = [{
    id: '01K00000000000000000000001',
    slug: 'general',
    name: 'General',
    parentId: null,
    displayOrder: 0,
    attributes: [],
  }];

  const draft: ListingDraft = {
    id: '01L00000000000000000000001',
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: '01U00000000000000000000001',
    businessId: null,
    categoryId: categories[0].id,
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

  const media: ListingMedia = {
    id: '01M00000000000000000000001',
    listingId: draft.id,
    sellerType: 'INDIVIDUAL',
    individualSellerUserId: draft.individualSellerUserId,
    businessId: null,
    objectBucket: 'listing-media-local',
    objectKey: 'listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
    originalFileName: 'bike.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    checksumSha256: null,
    uploadStatus: 'PENDING_UPLOAD',
    moderationStatus: 'NOT_SUBMITTED',
    uploadMethod: 'PUT',
    uploadUrl: '/api/v1/listings/01L00000000000000000000001/media/01M00000000000000000000001/content',
    version: 0,
    createdAt: '2026-06-16T12:01:00Z',
    updatedAt: '2026-06-16T12:01:00Z',
  };

  const image: ListingImage = {
    id: '01I00000000000000000000001',
    listingId: draft.id,
    mediaObjectId: media.id,
    displayOrder: 0,
    altText: 'Blue bike',
    moderationStatus: 'NOT_SUBMITTED',
    originalFileName: 'bike.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    uploadStatus: 'UPLOADED',
    objectBucket: 'listing-media-local',
    objectKey: media.objectKey,
    uploadUrl: media.uploadUrl,
    url: '/api/v1/listings/01L00000000000000000000001/media/01M00000000000000000000001/content',
    version: 0,
    createdAt: '2026-06-16T12:02:00Z',
    updatedAt: '2026-06-16T12:02:00Z',
  };

  const publicListing: PublicListing = {
    id: draft.id,
    sellerType: 'INDIVIDUAL',
    sellerDisplayName: 'Alex Seller',
    categoryId: categories[0].id,
    categorySlug: 'general',
    categoryName: 'General',
    title: 'Used bicycle',
    description: 'A reliable city bike.',
    condition: 'GOOD',
    conditionNotes: null,
    priceAmount: 250,
    currency: 'USD',
    negotiable: true,
    quantity: 1,
    publicCity: 'Irvine',
    publicRegion: 'CA',
    publishedAt: '2026-06-17T12:00:00Z',
    transactionNotice: 'Payment and delivery are arranged directly by participants.',
    images: [{
      id: image.id,
      displayOrder: 0,
      altText: 'Blue bike',
      originalFileName: 'bike.png',
      contentType: 'image/png',
      sizeBytes: 1024,
      uploadUrl: image.uploadUrl,
      url: '/api/v1/public/listing-media/01I00000000000000000000001',
    }],
  };

  const moderationCase: AdminListingModerationCase = {
    id: '01MC0000000000000000000001',
    caseStatus: 'OPEN',
    priority: 'NORMAL',
    assignedAdminUserId: null,
    assignedAdminDisplayName: null,
    version: 0,
    createdAt: '2026-06-17T12:00:00Z',
    updatedAt: '2026-06-17T12:00:00Z',
    resolvedAt: null,
    submittedByUserId: '01U00000000000000000000001',
    sellerId: '01U00000000000000000000001',
    sellerDisplayName: 'Alex Seller',
    listingId: draft.id,
    title: 'Used bicycle',
    sellerType: 'INDIVIDUAL',
    listingStatus: 'PENDING_REVIEW',
    listingModerationStatus: 'PENDING',
    priceAmount: 250,
    currency: 'USD',
    publicCity: 'Irvine',
    publicRegion: 'CA',
    sku: null,
    quantity: 1,
  };

  const moderationCaseDetail: AdminListingModerationCaseDetail = {
    moderationCase: {
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: '01A00000000000000000000001',
      assignedAdminDisplayName: 'Morgan Admin',
      version: 1,
    },
    listing: {
      ...draft,
      status: 'PENDING_REVIEW',
      moderationStatus: 'PENDING',
      version: 1,
      images: [{ ...image, moderationStatus: 'PENDING' }],
    },
    decisions: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(ListingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads categories through the gateway without browser tokens', () => {
    service.getCategories().subscribe(response => {
      expect(response).toEqual(categories);
    });

    const request = httpMock.expectOne('/api/v1/categories');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(categories);
  });

  it('creates listing drafts through the gateway without browser tokens', () => {
    service.createDraft({
      sellerType: 'INDIVIDUAL',
      categoryId: categories[0].id,
      title: 'Used bicycle',
      description: 'A reliable city bike.',
      condition: 'GOOD',
      price: { amount: 250, currency: 'USD' },
      negotiable: true,
      quantity: 1,
    }).subscribe(response => {
      expect(response).toEqual(draft);
    });

    const request = httpMock.expectOne('/api/v1/listings');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual(jasmine.objectContaining({
      sellerType: 'INDIVIDUAL',
      categoryId: categories[0].id,
      quantity: 1,
    }));
    request.flush(draft);
  });

  it('loads an owned listing draft through the gateway without browser tokens', () => {
    service.getListing(draft.id).subscribe(response => {
      expect(response).toEqual(draft);
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(draft);
  });

  it('loads public listing detail through the gateway without browser tokens', () => {
    service.getPublicListing(draft.id).subscribe(response => {
      expect(response).toEqual(publicListing);
    });

    const request = httpMock.expectOne(`/api/v1/public/listings/${draft.id}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(publicListing);
  });

  it('loads public approved listing browse through the gateway without browser tokens', () => {
    service.getPublicListings().subscribe(response => {
      expect(response).toEqual([publicListing]);
    });

    const request = httpMock.expectOne('/api/v1/public/listings');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([publicListing]);
  });

  it('loads individual marketplace search through the split public contract without browser tokens', () => {
    service.searchMarketplaceListings({
      q: 'bike',
      categoryId: categories[0].id,
      condition: 'GOOD',
      minPrice: 10,
      maxPrice: 300,
      city: 'Irvine',
      county: 'Orange County',
      sort: 'price_asc',
    }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
      expect(response.page.hasMore).toBeTrue();
      expect(response.page.nextCursor).toBe('next-page');
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/marketplace/listings/search');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.params.get('q')).toBe('bike');
    expect(request.request.params.get('categoryId')).toBe(categories[0].id);
    expect(request.request.params.get('condition')).toBe('GOOD');
    expect(request.request.params.get('minPrice')).toBe('10');
    expect(request.request.params.get('maxPrice')).toBe('300');
    expect(request.request.params.get('city')).toBe('Irvine');
    expect(request.request.params.get('county')).toBe('Orange County');
    expect(request.request.params.get('sort')).toBe('price_asc');
    request.flush({ data: [publicListing], page: { nextCursor: 'next-page', hasMore: true } });
  });

  it('omits empty marketplace search params including default sort', () => {
    service.searchMarketplaceListings({ q: '', sort: 'none' }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/marketplace/listings/search');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ data: [publicListing], page: { nextCursor: null, hasMore: false } });
  });

  it('sends marketplace search cursor and limit when loading more', () => {
    service.searchMarketplaceListings({ cursor: 'cursor-1', limit: 12 }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/marketplace/listings/search');
    expect(request.request.params.get('cursor')).toBe('cursor-1');
    expect(request.request.params.get('limit')).toBe('12');
    request.flush({ data: [publicListing], page: { nextCursor: null, hasMore: false } });
  });

  it('loads business store listing search through the split public contract without browser tokens', () => {
    service.searchBusinessStoreListings({
      q: 'plush',
      categoryId: categories[0].id,
      condition: 'NEW',
      minPrice: 5,
      maxPrice: 80,
      city: 'Irvine',
      county: 'Orange County',
      sort: 'price_desc',
    }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
      expect(response.page.hasMore).toBeTrue();
      expect(response.page.nextCursor).toBe('next-store-page');
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/stores/listings/search');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.params.get('q')).toBe('plush');
    expect(request.request.params.get('categoryId')).toBe(categories[0].id);
    expect(request.request.params.get('condition')).toBe('NEW');
    expect(request.request.params.get('minPrice')).toBe('5');
    expect(request.request.params.get('maxPrice')).toBe('80');
    expect(request.request.params.get('city')).toBe('Irvine');
    expect(request.request.params.get('county')).toBe('Orange County');
    expect(request.request.params.get('sort')).toBe('price_desc');
    request.flush({ data: [publicListing], page: { nextCursor: 'next-store-page', hasMore: true } });
  });

  it('omits empty business store search params including default sort', () => {
    service.searchBusinessStoreListings({ q: '', sort: 'none' }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/stores/listings/search');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ data: [publicListing], page: { nextCursor: null, hasMore: false } });
  });

  it('sends business store search cursor and limit when loading more', () => {
    service.searchBusinessStoreListings({ cursor: 'store-cursor-1', limit: 12 }).subscribe(response => {
      expect(response.data).toEqual([publicListing]);
    });

    const request = httpMock.expectOne(req => req.url === '/api/v1/public/stores/listings/search');
    expect(request.request.params.get('cursor')).toBe('store-cursor-1');
    expect(request.request.params.get('limit')).toBe('12');
    request.flush({ data: [publicListing], page: { nextCursor: null, hasMore: false } });
  });

  it('loads current seller listings through the gateway without browser tokens', () => {
    service.getMyListings().subscribe(response => {
      expect(response).toEqual([draft]);
    });

    const request = httpMock.expectOne('/api/v1/users/me/listings');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([draft]);
  });

  it('loads admin listing moderation queue through the gateway without browser tokens', () => {
    const pending = { ...draft, status: 'PENDING_REVIEW', moderationStatus: 'PENDING', version: 1 };

    service.getPendingModerationListings().subscribe(response => {
      expect(response).toEqual([pending]);
    });

    const request = httpMock.expectOne('/api/v1/admin/listings/moderation');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([pending]);
  });

  it('loads admin listing detail through the gateway without browser tokens', () => {
    const active = { ...draft, status: 'ACTIVE', moderationStatus: 'APPROVED', version: 2 };

    service.getAdminListing(draft.id).subscribe(response => {
      expect(response).toEqual(active);
    });

    const request = httpMock.expectOne(`/api/v1/admin/listings/${draft.id}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(active);
  });

  it('updates active listings as admin with optimistic locking through the gateway', () => {
    const active = { ...draft, status: 'ACTIVE', moderationStatus: 'APPROVED', version: 2 };

    service.updateActiveListingByAdmin(draft.id, 1, {
      categoryId: draft.categoryId,
      title: 'Admin edited bicycle',
      description: 'Updated active listing.',
      condition: 'GOOD',
      conditionNotes: null,
      price: { amount: 240, currency: 'USD' },
      negotiable: false,
      location: { city: 'Santa Ana', region: 'CA' },
      sku: null,
      quantity: 1,
      reason: 'Removed unsafe wording',
    }).subscribe(response => {
      expect(response).toEqual(active);
    });

    const request = httpMock.expectOne(`/api/v1/admin/listings/${draft.id}`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      categoryId: draft.categoryId,
      title: 'Admin edited bicycle',
      description: 'Updated active listing.',
      condition: 'GOOD',
      conditionNotes: null,
      price: { amount: 240, currency: 'USD' },
      negotiable: false,
      location: { city: 'Santa Ana', region: 'CA' },
      sku: null,
      quantity: 1,
      reason: 'Removed unsafe wording',
    });
    request.flush(active);
  });

  it('removes active listings as admin with optimistic locking through the gateway', () => {
    const removed = { ...draft, status: 'REMOVED_BY_ADMIN', moderationStatus: 'APPROVED', version: 2 };

    service.removeActiveListingByAdmin(draft.id, 1, {
      reason: 'Violates marketplace policy',
    }).subscribe(response => {
      expect(response).toEqual(removed);
    });

    const request = httpMock.expectOne(`/api/v1/admin/listings/${draft.id}/remove`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      reason: 'Violates marketplace policy',
    });
    request.flush(removed);
  });

  it('loads admin listing moderation cases through the gateway without browser tokens', () => {
    service.getListingModerationCases('assigned_to_me').subscribe(response => {
      expect(response).toEqual([moderationCase]);
    });

    const request = httpMock.expectOne('/api/v1/admin/moderation/listing-cases?filter=assigned_to_me');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([moderationCase]);
  });

  it('searches admin listing moderation cases through the gateway without browser tokens', () => {
    service.getListingModerationCases('open', 'vintage camera').subscribe(response => {
      expect(response).toEqual([moderationCase]);
    });

    const request = httpMock.expectOne('/api/v1/admin/moderation/listing-cases?filter=open&q=vintage%20camera');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([moderationCase]);
  });

  it('claims listing moderation cases with optimistic locking through the gateway', () => {
    service.claimListingModerationCase(moderationCase.id, 0).subscribe(response => {
      expect(response.caseStatus).toBe('CLAIMED');
      expect(response.assignedAdminUserId).toBe('01A00000000000000000000001');
    });

    const request = httpMock.expectOne(`/api/v1/admin/moderation/listing-cases/${moderationCase.id}/claim`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('0');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toBeNull();
    request.flush({
      ...moderationCase,
      caseStatus: 'CLAIMED',
      assignedAdminUserId: '01A00000000000000000000001',
      version: 1,
    });
  });

  it('releases listing moderation cases with optimistic locking through the gateway', () => {
    service.releaseListingModerationCase(moderationCase.id, 1).subscribe(response => {
      expect(response.caseStatus).toBe('OPEN');
      expect(response.assignedAdminUserId).toBeNull();
    });

    const request = httpMock.expectOne(`/api/v1/admin/moderation/listing-cases/${moderationCase.id}/release`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toBeNull();
    request.flush({
      ...moderationCase,
      version: 2,
    });
  });

  it('loads a listing moderation case detail through the gateway', () => {
    service.getListingModerationCaseDetail(moderationCase.id).subscribe(response => {
      expect(response).toEqual(moderationCaseDetail);
    });

    const request = httpMock.expectOne(`/api/v1/admin/moderation/listing-cases/${moderationCase.id}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(moderationCaseDetail);
  });

  it('resolves listing moderation cases with optimistic locking through the gateway', () => {
    service.resolveListingModerationCase(moderationCase.id, 1, {
      decision: 'APPROVE',
      reason: 'Listing looks good',
    }).subscribe(response => {
      expect(response.moderationCase.caseStatus).toBe('RESOLVED');
      expect(response.listing.status).toBe('ACTIVE');
    });

    const request = httpMock.expectOne(`/api/v1/admin/moderation/listing-cases/${moderationCase.id}/resolve`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      decision: 'APPROVE',
      reason: 'Listing looks good',
    });
    request.flush({
      ...moderationCaseDetail,
      moderationCase: {
        ...moderationCaseDetail.moderationCase,
        caseStatus: 'RESOLVED',
        version: 2,
        resolvedAt: '2026-06-17T12:30:00Z',
      },
      listing: {
        ...moderationCaseDetail.listing,
        status: 'ACTIVE',
        moderationStatus: 'APPROVED',
        version: 2,
      },
    });
  });

  it('updates listing drafts with optimistic locking through the gateway', () => {
    service.updateDraft(draft.id, draft.version, {
      sellerType: 'INDIVIDUAL',
      categoryId: categories[0].id,
      title: 'Updated bicycle',
      description: 'Freshly tuned city bike.',
      condition: 'LIKE_NEW',
      price: { amount: 275, currency: 'USD' },
      negotiable: false,
      quantity: 1,
    }).subscribe(response => {
      expect(response).toEqual({ ...draft, title: 'Updated bicycle', version: 1 });
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.headers.get('If-Match')).toBe('0');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ ...draft, title: 'Updated bicycle', version: 1 });
  });

  it('submits listing drafts for review with optimistic locking through the gateway', () => {
    const submitted = { ...draft, status: 'PENDING_REVIEW', moderationStatus: 'PENDING', version: 1 };

    service.submitForReview(draft.id, draft.version).subscribe(response => {
      expect(response).toEqual(submitted);
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}/submit`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('0');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(submitted);
  });

  it('requests listing media upload through the gateway without browser tokens', () => {
    service.requestMediaUpload(draft.id, {
      contentType: 'image/png',
      fileName: 'bike.png',
      sizeBytes: 1024,
    }).subscribe(response => {
      expect(response).toEqual(media);
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}/media/upload-request`);
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      contentType: 'image/png',
      fileName: 'bike.png',
      sizeBytes: 1024,
    });
    request.flush(media);
  });

  it('confirms listing media upload through the gateway without browser tokens', () => {
    const confirmed = {
      ...media,
      uploadStatus: 'UPLOADED' as const,
      version: 1,
    };

    service.confirmMediaUpload(draft.id, media.id, {
      sizeBytes: 1024,
    }).subscribe(response => {
      expect(response).toEqual(confirmed);
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}/media/${media.id}/confirm`);
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({ sizeBytes: 1024 });
    request.flush(confirmed);
  });

  it('uploads listing media bytes through the gateway when the backend returns an app URL', () => {
    const file = new File(['x'], 'bike.png', { type: 'image/png' });

    service.uploadMediaFile(media.uploadUrl, file).subscribe(response => {
      expect(response).toBeNull();
    });

    const request = httpMock.expectOne(media.uploadUrl);
    expect(request.request.method).toBe('PUT');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('Content-Type')).toBe('image/png');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(null);
  });

  it('uploads listing media bytes directly to external storage without gateway credentials', () => {
    const file = new File(['x'], 'bike.png', { type: 'image/png' });
    const uploadUrl = 'https://storage.example.test/listings/bike.png';

    service.uploadMediaFile(uploadUrl, file).subscribe(response => {
      expect(response).toBeNull();
    });

    const request = httpMock.expectOne(uploadUrl);
    expect(request.request.method).toBe('PUT');
    expect(request.request.withCredentials).toBeFalse();
    expect(request.request.headers.get('Content-Type')).toBe('image/png');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(null);
  });

  it('treats local demo listing media uploads as already stored', () => {
    const file = new File(['x'], 'bike.png', { type: 'image/png' });
    const uploadUrl = 'local-demo://listing-media-local/listings/01L00000000000000000000001/01M00000000000000000000001/bike.png';

    service.uploadMediaFile(uploadUrl, file).subscribe(response => {
      expect(response).toBeUndefined();
    });

    httpMock.expectNone(uploadUrl);
  });

  it('updates listing image order through the gateway without browser tokens', () => {
    service.updateListingImages(draft.id, {
      images: [{ mediaId: media.id, altText: 'Blue bike' }],
    }).subscribe(response => {
      expect(response).toEqual([image]);
    });

    const request = httpMock.expectOne(`/api/v1/listings/${draft.id}/images`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      images: [{ mediaId: media.id, altText: 'Blue bike' }],
    });
    request.flush([image]);
  });

  it('sends admin listing moderation decisions with optimistic locking through the gateway', () => {
    service.decideListing(draft.id, 1, {
      decision: 'APPROVE',
      reason: 'Looks complete.',
    }).subscribe(response => {
      expect(response.decision).toBe('APPROVE');
      expect(response.listingId).toBe(draft.id);
    });

    const request = httpMock.expectOne(`/api/v1/admin/listings/${draft.id}/decision`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual({
      decision: 'APPROVE',
      reason: 'Looks complete.',
    });
    request.flush({
      id: '01D00000000000000000000001',
      listingId: draft.id,
      decision: 'APPROVE',
      reason: 'Looks complete.',
      reviewerUserId: '01A00000000000000000000001',
      listingVersion: 2,
      createdAt: '2026-06-17T12:00:00Z',
    });
  });
});
