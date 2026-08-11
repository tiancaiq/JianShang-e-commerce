import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { BusinessOrderService } from './business-order.service';

describe('BusinessOrderService', () => {
  let service: BusinessOrderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BusinessOrderService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads a bounded filtered queue with the opaque cursor unchanged', () => {
    service.list('business/one', {
      status: 'PENDING_ACCEPTANCE',
      cursor: 'v1.opaque-cursor',
      limit: 50,
    }).subscribe();

    const request = http.expectOne(req =>
      req.url === `${environment.apiGatewayUrl}/api/v1/businesses/business%2Fone/orders`
      && req.params.get('status') === 'PENDING_ACCEPTANCE'
      && req.params.get('cursor') === 'v1.opaque-cursor'
      && req.params.get('limit') === '50');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBeTrue();
    request.flush({ items: [], page: { nextCursor: null, hasMore: false } });
  });

  it('forwards the seller-facing cancelled queue filter', () => {
    service.list('business-1', { status: 'CANCELLED' }).subscribe();

    const request = http.expectOne(req =>
      req.url === `${environment.apiGatewayUrl}/api/v1/businesses/business-1/orders`
      && req.params.get('status') === 'CANCELLED');
    expect(request.request.method).toBe('GET');
    request.flush({ items: [], page: { nextCursor: null, hasMore: false } });
  });

  it('reads one business-order detail without sending identity or finance hints', () => {
    service.detail('business-1', 'order-1').subscribe();

    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/businesses/business-1/orders/order-1`,
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.keys()).not.toContain('X-User-Id');
    expect(request.request.headers.keys()).not.toContain('X-Roles');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({});
  });

  it('sends bounded optimistic and idempotency headers for fulfillment commands', () => {
    service.startProcessing('business-1', 'order-1', 4, 'seller-processing-key').subscribe();

    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/businesses/business-1/orders/order-1/processing`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('If-Match')).toBe('4');
    expect(request.request.headers.get('Idempotency-Key')).toBe('seller-processing-key');
    expect(request.request.headers.keys()).not.toContain('X-User-Id');
    request.flush({
      businessOrderId: 'order-1', fulfillmentStatus: 'PROCESSING', version: 5,
      updatedAt: '2026-08-03T15:00:00Z', shipment: null,
    });
  });

  it('sends seller return commands with scoped paths and concurrency headers', () => {
    service.receiveReturn('business/one', 'group/one', 'return/one', 3,
      'seller-return-key', 'DO_NOT_RESTOCK').subscribe();

    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/businesses/business%2Fone/orders/group%2Fone/returns/return%2Fone/receive`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('3');
    expect(request.request.headers.get('Idempotency-Key')).toBe('seller-return-key');
    expect(request.request.body).toEqual({ inventoryDisposition: 'DO_NOT_RESTOCK' });
    expect(request.request.withCredentials).toBeTrue();
    request.flush({});
  });
});
