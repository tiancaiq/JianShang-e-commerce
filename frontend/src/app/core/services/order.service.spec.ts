import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { OrderService } from './order.service';

describe('OrderService returns', () => {
  let service: OrderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(OrderService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests one whole business group with optimistic and idempotency headers', () => {
    service.requestReturn('order/one', 'group/one', 4, 'buyer-return-key',
      'NOT_AS_EXPECTED', 'Whole group').subscribe();

    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/orders/order%2Fone/groups/group%2Fone/returns`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('4');
    expect(request.request.headers.get('Idempotency-Key')).toBe('buyer-return-key');
    expect(request.request.body).toEqual({ reasonCode: 'NOT_AS_EXPECTED', comment: 'Whole group' });
    expect(request.request.withCredentials).toBeTrue();
    request.flush({});
  });
});
