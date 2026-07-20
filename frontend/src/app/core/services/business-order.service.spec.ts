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
});
