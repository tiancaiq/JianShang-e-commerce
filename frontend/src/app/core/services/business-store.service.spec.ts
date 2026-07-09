import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BusinessStore, BusinessStoreContext } from '../models/business-store.model';
import { BusinessStoreService } from './business-store.service';

describe('BusinessStoreService', () => {
  let service: BusinessStoreService;
  let httpMock: HttpTestingController;

  const store: BusinessStore = {
    id: '01JY0000000000000000000100',
    businessId: '01JY0000000000000000000003',
    slug: 'acme-trading',
    name: 'Acme Trading',
    description: 'Local goods',
    logoUrl: 'https://example.com/logo.png',
    bannerUrl: null,
    supportEmail: 'help@example.com',
    supportPhone: '+19495550000',
    status: 'ACTIVE',
    version: 1,
    createdAt: '2026-07-08T12:00:00Z',
    updatedAt: '2026-07-08T12:05:00Z',
  };
  const context: BusinessStoreContext = {
    businessId: store.businessId,
    businessLegalName: 'Acme Trading LLC',
    businessStatus: 'ACTIVE',
    membershipRole: 'OWNER',
    permissions: ['LISTING_DRAFT_CREATE'],
    store,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(BusinessStoreService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads an authorized business store through the gateway', () => {
    service.getBusinessStore(store.businessId).subscribe(response => {
      expect(response).toEqual(store);
    });

    const request = httpMock.expectOne(`/api/v1/businesses/${store.businessId}/store`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: store });
  });

  it('loads the current business store context through the gateway', () => {
    service.getCurrentStoreContext().subscribe(response => {
      expect(response).toEqual(context);
    });

    const request = httpMock.expectOne('/api/v1/businesses/me/store-context');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ data: context });
  });

  it('allows a missing current business store context', () => {
    service.getCurrentStoreContext().subscribe(response => {
      expect(response).toBeNull();
    });

    const request = httpMock.expectOne('/api/v1/businesses/me/store-context');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    request.flush({ data: null });
  });

  it('updates a business store with If-Match version', () => {
    const body = {
      name: 'Acme Trading',
      slug: 'acme-trading',
      description: 'Local goods',
      logoUrl: 'https://example.com/logo.png',
      bannerUrl: null,
      supportEmail: 'help@example.com',
      supportPhone: '+19495550000',
    };

    service.updateBusinessStore(store.businessId, body, 1).subscribe(response => {
      expect(response).toEqual(store);
    });

    const request = httpMock.expectOne(`/api/v1/businesses/${store.businessId}/store`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('If-Match')).toBe('1');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    expect(request.request.body).toEqual(body);
    request.flush({ data: store });
  });

  it('loads a public store without session credentials', () => {
    service.getPublicStore(store.slug).subscribe(response => {
      expect(response).toEqual(store);
    });

    const request = httpMock.expectOne(`/api/v1/stores/${store.slug}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeFalse();
    request.flush({ data: store });
  });
});
