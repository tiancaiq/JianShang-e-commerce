import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InventoryBalance, InventoryCatalogPage, InventoryMovementPage } from '../models/inventory.model';
import { InventoryService } from './inventory.service';

describe('InventoryService', () => {
  let service: InventoryService;
  let httpMock: HttpTestingController;

  const businessId = '01B00000000000000000000001';
  const listingId = '01L00000000000000000000001';
  const balance: InventoryBalance = {
    id: '01I00000000000000000000001',
    businessId,
    listingId,
    skuSnapshot: 'SKU-1',
    onHand: 8,
    reserved: 0,
    available: 8,
    version: 0,
    initializedAt: '2026-07-17T08:00:00Z',
    updatedAt: '2026-07-17T08:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(InventoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads a filtered business inventory catalog', () => {
    const page: InventoryCatalogPage = { data: [], page: { nextCursor: null, hasMore: false } };
    service.list(businessId, { q: 'keyboard', listingStatus: 'ACTIVE', limit: 12 })
      .subscribe(response => expect(response).toEqual(page));

    const request = httpMock.expectOne(req =>
      req.url === `/api/v1/businesses/${businessId}/inventory`
      && req.params.get('q') === 'keyboard'
      && req.params.get('listingStatus') === 'ACTIVE'
      && req.params.get('limit') === '12',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    request.flush(page);
  });

  it('initializes stock with an idempotency key', () => {
    service.initialize(businessId, listingId, { onHand: 8, note: 'Opening count' })
      .subscribe(response => expect(response).toEqual(balance));

    const request = httpMock.expectOne(`/api/v1/businesses/${businessId}/inventory/${listingId}/initialize`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({ onHand: 8, note: 'Opening count' });
    request.flush({ data: balance });
  });

  it('adjusts stock with optimistic version and idempotency headers', () => {
    service.adjust(businessId, listingId, 4, {
      operation: 'ADJUST',
      quantity: -2,
      reason: 'STOCK_COUNT_CORRECTION',
      note: null,
    }).subscribe(response => expect(response.onHand).toBe(8));

    const request = httpMock.expectOne(`/api/v1/businesses/${businessId}/inventory/${listingId}/adjustments`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('4');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({ data: balance });
  });

  it('loads the append-only movement page', () => {
    const page: InventoryMovementPage = { data: [], page: { nextCursor: null, hasMore: false } };
    service.movements(businessId, listingId).subscribe(response => expect(response).toEqual(page));

    const request = httpMock.expectOne(req =>
      req.url === `/api/v1/businesses/${businessId}/inventory/${listingId}/movements`
      && req.params.get('limit') === '20',
    );
    expect(request.request.method).toBe('GET');
    request.flush(page);
  });
});
