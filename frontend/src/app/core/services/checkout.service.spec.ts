import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CheckoutService } from './checkout.service';
import { environment } from '../../../environments/environment';

describe('CheckoutService', () => {
  let service: CheckoutService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CheckoutService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('retains one idempotency key for retries of the same cart and address', () => {
    const request = {
      cartVersion: 4,
      addressId: '01A00000000000000000000001',
    };

    service.create(request).subscribe();
    const first = http.expectOne(`${environment.apiGatewayUrl}/api/v1/checkouts`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({});

    service.create(request).subscribe();
    const second = http.expectOne(`${environment.apiGatewayUrl}/api/v1/checkouts`);
    const secondKey = second.request.headers.get('Idempotency-Key');
    second.flush({});

    expect(firstKey).toBeTruthy();
    expect(secondKey).toBe(firstKey);
    expect(first.request.body).toEqual(request);
  });

  it('uses a stable cancel key and never sends payment data', () => {
    const checkoutId = '01C00000000000000000000001';

    service.cancel(checkoutId).subscribe();
    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/checkouts/${checkoutId}/cancel`,
    );

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({});
  });

  it('derives the payment-intent key from checkout identity so refreshes replay safely', () => {
    const checkoutId = '01C00000000000000000000001';

    service.createPaymentIntent(checkoutId).subscribe();
    const request = http.expectOne(
      `${environment.apiGatewayUrl}/api/v1/checkouts/${checkoutId}/payment-intent`,
    );

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('Idempotency-Key'))
      .toBe(`checkout-payment:${checkoutId}`);
    request.flush({});
  });
});
