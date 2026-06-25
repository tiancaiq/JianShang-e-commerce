import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ListingService } from './listing.service';
import { Category, ListingDraft, ListingMedia } from '../models/listing.model';

describe('ListingService', () => {
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
    uploadMethod: 'LOCAL_DEMO',
    uploadUrl: 'local-demo://listing-media-local/listings/01L00000000000000000000001/01M00000000000000000000001/bike.png',
    version: 0,
    createdAt: '2026-06-16T12:01:00Z',
    updatedAt: '2026-06-16T12:01:00Z',
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
});
